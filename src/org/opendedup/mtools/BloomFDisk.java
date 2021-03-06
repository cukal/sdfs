/*******************************************************************************
 * Copyright (C) 2016 Sam Silverberg sam.silverberg@gmail.com	
 *
 * This file is part of OpenDedupe SDFS.
 *
 * OpenDedupe SDFS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * OpenDedupe SDFS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Foobar.  If not, see <http://www.gnu.org/licenses/>.
 *******************************************************************************/
package org.opendedup.mtools;

import java.io.File;




import java.io.IOException;
import java.util.TreeMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.opendedup.collections.ShardedFileByteArrayLongMap.KeyBlob;
import org.apache.commons.io.FileUtils;
import org.opendedup.collections.LongByteArrayMap;
import org.opendedup.collections.SparseDataChunk;
import org.opendedup.hashing.LargeBloomFilter;
import org.opendedup.logging.SDFSLogger;
import org.opendedup.sdfs.Main;
import org.opendedup.sdfs.io.HashLocPair;
import org.opendedup.sdfs.io.WritableCacheBuffer.BlockPolicy;
import org.opendedup.sdfs.notification.FDiskEvent;
import org.opendedup.sdfs.notification.SDFSEvent;
import org.opendedup.sdfs.servers.HCServiceProxy;
import com.google.common.hash.Funnel;
import com.google.common.hash.PrimitiveSink;

public class BloomFDisk {
	private AtomicLong files = new AtomicLong(0);
	private FDiskEvent fEvt = null;
	transient LargeBloomFilter bf = null;
	private boolean failed = false;
	private transient RejectedExecutionHandler executionHandler = new BlockPolicy();
	private transient BlockingQueue<Runnable> worksQueue = new SynchronousQueue<Runnable>();
	private transient ThreadPoolExecutor executor = new ThreadPoolExecutor(Main.writeThreads, Main.writeThreads,
			10, TimeUnit.SECONDS, worksQueue, new ProcessPriorityThreadFactory(Thread.MIN_PRIORITY), executionHandler);
	public static boolean closed;

	public BloomFDisk() {

	}

	public BloomFDisk(SDFSEvent evt) throws FDiskException {
		long entries = HCServiceProxy.getSize();
		init(evt, entries);
	}

	public BloomFDisk(SDFSEvent evt, long entries) throws FDiskException {
		init(evt, entries);
	}

	public SDFSEvent getEvt() {
		return this.fEvt;
	}

	public void init(SDFSEvent evt, long entries) throws FDiskException {
		if (entries == 0)
			entries = HCServiceProxy.getSize();
		File f = new File(Main.dedupDBStore);
		if (!f.exists()) {
			SDFSEvent.fdiskInfoEvent("FDisk Will not start because the volume has not been written too", evt)
					.endEvent("FDisk Will not start because the volume has not been written too");
			return;
		}
		try {
			long sz = Main.volume.getFiles();
			fEvt = SDFSEvent.fdiskInfoEvent("Starting BFDISK for " + Main.volume.getName() + " file size = " + sz, evt);
			fEvt.maxCt = sz;

			SDFSLogger.getLog().info("entries = " + entries);
			FileUtils.deleteDirectory(new File(new File(Main.dedupDBStore).getParent()+ File.separator + "tmp"));
			this.bf = new LargeBloomFilter(new File(new File(Main.dedupDBStore).getParent()+ File.separator + "tmp"),entries, .1, false,false,false);
			SDFSLogger.getLog().info("Starting BloomFilter FDISK for " + Main.volume.getName());
			long start = System.currentTimeMillis();
			this.traverse(f);
			executor.shutdown();
			while (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
				SDFSLogger.getLog().debug("Awaiting fdisk completion of threads.");
			}
			if (failed)
				throw new IOException("BFDisk traverse failed");
			SDFSLogger.getLog().info(
					"took [" + (System.currentTimeMillis() - start) / 1000 + "] seconds to check [" + files + "].");

			fEvt.endEvent(
					"took [" + (System.currentTimeMillis() - start) / 1000 + "] seconds to check [" + files + "].");
		} catch (Exception e) {
			SDFSLogger.getLog().info("fdisk failed", e);
			fEvt.endEvent("fdisk failed because [" + e.toString() + "]", SDFSEvent.ERROR);
			this.failed = true;
			this.vanish();
			throw new FDiskException(e);
		}
	}

	public LargeBloomFilter getResults() {
		return this.bf;
	}

	private void traverse(File dir) throws IOException {
		SDFSLogger.getLog().info("traversing " + dir.getPath());
		if (closed)
			throw new IOException("FDISK Closed");
		if (dir.isDirectory()) {
			if (failed)
				throw new IOException("BFDisk traverse failed");
			String[] children = dir.list();
			for (int i = 0; i < children.length; i++) {
				traverse(new File(dir, children[i]));
			}
		} else {
			if (failed)
				throw new IOException("BFDisk traverse failed");
			if (dir.getPath().endsWith(".map") || dir.getPath().endsWith(".map.lz4")) {
				executor.execute(new CheckDedupFile(this, dir));
			}
		}
	}

	private void checkDedupFile(File mapFile) throws IOException {
		SDFSLogger.getLog().info("checking " + mapFile);
		if (closed) {
			this.failed = true;
			return;
		}
		LongByteArrayMap mp = null;
		try {
			if(mapFile.getName().endsWith(".lz4"))
				mp = LongByteArrayMap.getMap(mapFile.getName().substring(0, mapFile.getName().length()-8));
			else
				mp = LongByteArrayMap.getMap(mapFile.getName().substring(0, mapFile.getName().length()-4));
			if (closed) {
				this.failed = true;
				return;
			}
			mp.iterInit();
			SparseDataChunk ck = mp.nextValue(Main.refCount);
			while (ck != null) {
				if (closed) {
					this.failed = true;
					return;
				}
				TreeMap<Integer,HashLocPair> al = ck.getFingers();
				for (HashLocPair p : al.values()) {
					bf.put(p.hash);
					
				}
				ck = mp.nextValue(Main.refCount);
			}

			synchronized (fEvt) {
				fEvt.curCt++;
			}
		} catch (Throwable e) {
			SDFSLogger.getLog().info("error while checking file [" + mapFile.getPath() + "]", e);
			this.failed = true;
		} finally {
			mp.close();
			mp = null;
		}
		this.files.incrementAndGet();
	}

	Funnel<KeyBlob> kbFunnel = new Funnel<KeyBlob>() {
		/**
		 * 
		 */
		private static final long serialVersionUID = -1612304804452862219L;

		/**
		 * 
		 */

		@Override
		public void funnel(KeyBlob key, PrimitiveSink into) {
			into.putBytes(key.key);
		}
	};

	private static class CheckDedupFile implements Runnable {

		BloomFDisk fd = null;
		File f = null;

		protected CheckDedupFile(BloomFDisk fd, File f) {
			SDFSLogger.getLog().info("init " + f.getPath());
			this.fd = fd;
			this.f = f;
		}

		@Override
		public void run() {
			try {
				SDFSLogger.getLog().info("running " + f.getPath());
				fd.checkDedupFile(f);
			} catch (Exception e) {
				SDFSLogger.getLog().error("error doing fdisk", e);
			}
		}
	}

	private final static class ProcessPriorityThreadFactory implements ThreadFactory {

		private final int threadPriority;

		public ProcessPriorityThreadFactory(int threadPriority) {
			this.threadPriority = threadPriority;
		}

		@Override
		public Thread newThread(Runnable r) {
			Thread thread = new Thread(r);
			thread.setPriority(threadPriority);
			return thread;
		}

	}

	public void vanish() {
		if (bf != null)
			this.bf.vanish();
	}

}
