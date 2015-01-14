package plugins.schwachkopfeinsteck;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.WeakHashMap;
import java.util.Map.Entry;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepository;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchWaiter;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.Metadata;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientRequester;
import freenet.client.async.PersistenceDisabledException;
import freenet.client.async.SnoopMetadata;
import freenet.client.async.TooManyFilesInsertException;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.io.FileUtil;
import freenet.support.plugins.helpers1.PluginContext;

public class RepositoryManager {

	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerClass(GitPlugin.class);
	}

	public static class RepositoryWrapper {
		public final String name;
		public final Repository db;
		public final ReentrantReadWriteLock rwLock;

		RepositoryWrapper(String name2, Repository db2, ReentrantReadWriteLock rwLock2) {
			name = name2;
			db = db2;
			rwLock = rwLock2;
		}
	}

	private final PluginContext pluginContext;
	private final File cacheDir;
	private final WeakHashMap<String, ReentrantReadWriteLock> locks = new WeakHashMap<String, ReentrantReadWriteLock>();
	private final WeakHashMap<String, Repository> dbCache = new WeakHashMap<String, Repository>();
	private final HashMap<String, ClientRequester> jobs = new HashMap<String, ClientRequester>();

	RepositoryManager(File cachedir, PluginContext plugincontext) {
		cacheDir = cachedir;
		pluginContext = plugincontext;
	}

	private ReentrantReadWriteLock getRRWLock(String name) {
		ReentrantReadWriteLock result;
		synchronized(locks) {
			result = locks.get(name);
			if (result == null) {
				result = new ReentrantReadWriteLock(true);
				locks.put(name, result);
			}
		}
		return result;
	}

	private Repository internalGetRepository(String name) throws IOException {
		Repository result;
		synchronized(dbCache) {
			result = dbCache.get(name);
			if (result == null) {
				File reposDir = new File(cacheDir, name);
				if (!reposDir.exists()) {
					return null;
				}
				result = new FileRepository(reposDir);
				dbCache.put(name, result);
			}
		}
		return result;
	}

	public RepositoryWrapper getRepository(String name) throws IOException {
		Repository db = internalGetRepository(name);
		if (db == null) {
			return null;
		}
		ReentrantReadWriteLock lock = getRRWLock(name);
		return new RepositoryWrapper(name, db, lock);
	}

	public static File ensureCacheDirExists(String dir) throws IOException {
		File newDir = new File(dir);
		if (newDir.exists()) {
			if (!newDir.isDirectory()) {
				throw new IOException("Not a directory: "+newDir.getAbsolutePath());
			}
			return newDir;
		}
		if (newDir.mkdirs()) {
			return newDir;
		}
		throw new IOException("Unable to create cache directory: "+newDir.getAbsolutePath());
	}

	/**
	 * get the internal repository name from freenet uri.
	 * must be the request uri.
	 */
	public static String getRepositoryName(FreenetURI uri) {
		String docName = uri.getDocName();
		uri = uri.setKeyType("SSK");
		String reposName = uri.setDocName(null).setMetaString(null).toString(false, false);
		return new String(reposName + '@' + docName);
	}

	public String getCacheDir() {
		return cacheDir.getPath();
	}

	public void deleteRepository(String reposName) {
		ReentrantReadWriteLock lock = getRRWLock(reposName);
		synchronized (lock) {
			File repos = new File(cacheDir, reposName);
			FileUtil.removeAll(repos);
		}
	}

	public void updateDescription(String repos, String desc) throws IOException {
		File reposFile = new File(cacheDir, repos);
		updateDescription(reposFile, desc);
	}

	private void updateDescription(File repos, String desc) throws IOException {
		ReentrantReadWriteLock lock = getRRWLock(repos.getName());
		synchronized (lock) {
			File descfile = new File(repos, "description");
			if (descfile.exists()) {
				descfile.delete();
			}
			InputStream is = new ByteArrayInputStream(desc.getBytes("UTF-8"));
			FileUtil.writeTo(is, descfile);
		}
	}

	private void updateEditionHint(File repos, long edition) throws IOException {
		ReentrantReadWriteLock lock = getRRWLock(repos.getName());
		synchronized (lock) {
			File descfile = new File(repos, "EditionHint");
			if (descfile.exists()) {
				descfile.delete();
			}
			InputStream is = new ByteArrayInputStream(Long.toString(edition).getBytes("UTF-8"));
			FileUtil.writeTo(is, descfile);
		}
	}

	private long getEditionHint(File repos) {
		ReentrantReadWriteLock lock = getRRWLock(repos.getName());
		StringBuilder hint;
		synchronized (lock) {
			File hintfile = new File(repos, "EditionHint");
			if (!hintfile.exists()) {
				return -1;
			}
			try {
				hint = FileUtil.readUTF(hintfile);
			} catch (IOException e) {
				Logger.error(this, "Failed to read EditionHint for: "+repos.getName()+". Forcing full upload.");
				return -1;
			}
		}
		return Fields.parseLong(hint.toString(), -1);
	}

	private void tryCreateRepository(String reposName) throws IOException {
		tryCreateRepository(reposName, null);
	}

	public void tryCreateRepository(String reposName, String description) throws IOException {
		File reposDir = new File(cacheDir, reposName);
		Repository repos;
		repos = new FileRepository(reposDir);
		repos.create(true);
		if (description != null) {
			updateDescription(reposDir, description);
		}
	}

	@Deprecated
	public File getCacheDirFile() {
		return cacheDir;
	}

	public void kill() {
		// TODO stopp lockking, kill all jobs.
		// empty caches.
	}

	public FreenetURI insert(RepositoryWrapper rw, FreenetURI fetchURI, FreenetURI insertURI) throws InsertException {
		File reposDir = new File(cacheDir, rw.name);

		//get the edition hint
		long hint = getEditionHint(reposDir);
		HashMap<String, FreenetURI> packList = null;
		if (hint > -1) {
			// it seems the repository was inserted before, try to fetch the manifest to reuse the pack files
			FreenetURI u = fetchURI.setSuggestedEdition(hint).sskForUSK();
			packList = getPackList(u);
		}

		RequestClient rc = new RequestClient() {
			@Override
			public boolean persistent() {
				return false;
			}
			@Override
			public boolean realTimeFlag() {
				return false;
			}
			
		};
		 ClientContext x = pluginContext.clientCore.clientContext;
		InsertContext iCtx = pluginContext.hlsc.getInsertContext(true);
		iCtx.compressorDescriptor = "LZMA";
		VerboseWaiter pw = new VerboseWaiter(rc);
		ReposInserter1 dmp = null;
		try {
			dmp = new ReposInserter1(pw, packList, reposDir, rw.db, (short) 1, insertURI.setMetaString(null), "index.html", iCtx, pluginContext.clientCore.clientContext, pluginContext.clientCore.tempBucketFactory, false, (byte[])null);
		} catch (TooManyFilesInsertException e2) {
			// TODO Auto-generated catch block
			e2.printStackTrace();
		}
		
		iCtx.eventProducer.addEventListener(pw);
		
		try {
			pluginContext.clientCore.clientContext.start(dmp);
		} catch (PersistenceDisabledException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		
		FreenetURI result;
		try {
			result = pw.waitForCompletion();
		} finally {
			iCtx.eventProducer.removeEventListener(pw);
		}
		try {
			updateEditionHint(reposDir, result.getEdition());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return result;
	}

	public static class Snooper implements SnoopMetadata {
		private Metadata metaData;

		Snooper() {
		}

		public boolean snoopMetadata(Metadata meta, ClientContext context) {
			if (meta.isSimpleManifest()) {
				metaData = meta;
				return true;
			}
			return false;
		}
	}

	// get the fragment 'pack files list' from metadata, expect a ssk
	private HashMap<String, FreenetURI> getPackList(FreenetURI uri) {
		RequestClient rc = new RequestClient() {
			@Override
			public boolean persistent() {
				return false;
			}
			@Override
			public boolean realTimeFlag() {
				return false;
			}
			
		};
		// get the list for reusing pack files
		Snooper snooper = new Snooper();
		FetchContext context = pluginContext.hlsc.getFetchContext();
		FetchWaiter fw = new FetchWaiter(rc);
		ClientGetter get = new ClientGetter(fw, uri.setMetaString(new String[]{"fake"}), context, RequestStarter.INTERACTIVE_PRIORITY_CLASS, null);
		get.setMetaSnoop(snooper);
		try {
			get.start(pluginContext.clientCore.clientContext);
			fw.waitForCompletion();
		} catch (FetchException e) {
			Logger.error(this, "Fetch failure.", e);
		}

		if (snooper.metaData == null) {
			// nope. force a full insert
			return null;
		}
		HashMap<String, Metadata> list;
		try {
			// FIXME deal with MultiLevelMetadata, the pack dir can get huge
			list = snooper.metaData.getDocument("objects").getDocument("pack").getDocuments();
		} catch (Throwable t) {
			Logger.error(this, "Error transforming metadata, really a git repository? Or a Bug/MissingFeature.", t);
			return null;
		}
		HashMap<String, FreenetURI> result = new HashMap<String, FreenetURI>();
		for (Entry<String, Metadata> e:list.entrySet()) {
			String n = e.getKey();
			Metadata m = e.getValue();
			if (m.isSingleFileRedirect()) {
				// already a redirect, reuse it
				FreenetURI u = m.getSingleTarget();
				result.put(n, u);
			} else {
				FreenetURI u = uri.setMetaString(new String[]{"objects", "pack", n});
				result.put(n, u);
			}
		}
		return result;
	}
}
