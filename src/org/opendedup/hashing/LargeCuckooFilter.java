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
package org.opendedup.hashing;

import java.io.File;

import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.opendedup.collections.ShardedFileByteArrayLongMap.KeyBlob;
import org.opendedup.logging.SDFSLogger;
import org.opendedup.sdfs.Main;
import org.opendedup.sdfs.io.WritableCacheBuffer.BlockPolicy;
import org.opendedup.util.CommandLineProgressBar;

public class LargeCuckooFilter implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private static final int AR_SZ = 256;
	transient LCF[] bfs = new LCF[AR_SZ];
	private transient RejectedExecutionHandler executionHandler = new BlockPolicy();
	private transient SynchronousQueue<Runnable> worksQueue = new SynchronousQueue<Runnable>();

	private transient ThreadPoolExecutor executor = null;

	public LargeCuckooFilter() {

	}

	public LargeCuckooFilter(LCF[] bfs) {
		this.bfs = bfs;
	}
	
	public long getSize() {
		long sz = 0;
		for(LCF fp : bfs ) {
			sz+=fp.filter.getCount();
		}
		return sz;
	}

	public static boolean exists(File dir) {
		for (int i = 0; i < AR_SZ; i++) {
			File f = new File(dir.getPath() + File.separator + "lbf" + i
					+ ".nbf");
			if (!f.exists())
				return false;

		}
		return true;
	}

	public LargeCuckooFilter(File dir, long sz, boolean fb) throws IOException {
		executor = new ThreadPoolExecutor(Main.writeThreads, Main.writeThreads,
				10, TimeUnit.SECONDS, worksQueue, executionHandler);
		if(!dir.exists())
			dir.mkdirs();
		bfs = new LCF[AR_SZ];
		CommandLineProgressBar bar = null;
		if (fb)
			bar = new CommandLineProgressBar("Loading BloomFilters",
					bfs.length, System.out);
		long isz = sz / bfs.length;
		for (int i = 0; i < bfs.length; i++) {
			File f = new File(dir.getPath() + File.separator + "lbf" + i
					+ ".nbf");
			FBLoader th = new FBLoader();
			th.bfs = bfs;
			th.pos = i;
			th.f = f;
			th.sz = isz;
			executor.execute(th);
			//bfs[i] = new FLBF(isz, fpp, f, sync);
			if (bar != null)
				bar.update(i);

		}
		executor.shutdown();
		if (fb) {
			bar.finish();
			System.out.println("Waiting for last bloomfilters to load");
		}
		try {
			while (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
				SDFSLogger.getLog().debug(
						"Awaiting bloomload completion of threads.");
				if (fb) {
					System.out.print(".");
				}

			}
		} catch (Exception e) {
			SDFSLogger.getLog().error("error waiting for thermination", e);
		}
	}

	private LCF getMap(byte[] hash) {

		int hashb = hash[1];
		if (hashb < 0) {
			hashb = ((hashb * -1) + 127);
		}
		LCF m = bfs[hashb];
		return m;
	}
	
	public void remove(byte [] b) throws IOException {
		 getMap(b).remove(new KeyBlob(b));
	}

	public boolean mightContain(byte[] b) {
		return getMap(b).mightContain(new KeyBlob(b));
	}

	public void put(byte[] b) {
		getMap(b).put(new KeyBlob(b));
	}

	public void save(File dir) throws IOException {
		CommandLineProgressBar bar = new CommandLineProgressBar(
				"Saving BloomFilters", bfs.length, System.out);
		//SDFSLogger.getLog().info("saving bloomfilter to " + dir.getPath());
		if(!dir.exists())
			dir.mkdirs();
		executor = new ThreadPoolExecutor(Main.writeThreads, Main.writeThreads,
				100, TimeUnit.SECONDS, worksQueue, executionHandler);
		for (int i = 0; i < bfs.length; i++) {
			FBSaver th = new FBSaver();
			th.pos = i;
			th.bfs = bfs;
			executor.execute(th);
			bar.update(i);
		}
		executor.shutdown();
		System.out.println("Waiting for last bloomfilters to save");
		try {
			while (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
				SDFSLogger.getLog().debug(
						"Awaiting bloomfilter to finish save.");
				System.out.print(".");
			}
		} catch (Exception e) {
			SDFSLogger.getLog().error("error waiting for thermination", e);
		}
		bar.finish();
	}

	public LCF[] getArray() {
		return this.bfs;
	}

	public static void main(String[] args) {
		int[] ht = new int[32];
		for (int i = 0; i < 128; i++) {
			int z = i / 4;
			ht[z]++;
		}
		for (int i : ht) {
			System.out.println("i=" + i);
		}
	}

	private static class FBLoader implements Runnable {
		transient LCF[] bfs = null;
		int pos;
		File f;
		long sz;
		@Override
		public void run() {
			try {
			bfs[pos] = new LCF(sz,f);
			}catch(Exception e) {
				SDFSLogger.getLog().error("unable to create bloom filter",e);
			}

		}

	}

	private static class FBSaver implements Runnable {
		transient LCF[] bfs = null;
		int pos;

		@Override
		public void run() {
			try {
				bfs[pos].save();
			} catch (IOException e1) {
				SDFSLogger.getLog().error("unable to save", e1);
			}

		}

	}

}
