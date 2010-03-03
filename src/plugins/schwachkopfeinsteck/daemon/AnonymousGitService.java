package plugins.schwachkopfeinsteck.daemon;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PacketLineIn;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.UploadPack;

import freenet.support.Logger;
import freenet.support.incubation.server.AbstractService;

public class AnonymousGitService implements AbstractService {

	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerClass(AnonymousGitService.class);
	}

	private final boolean isReadOnly;

	public AnonymousGitService(boolean readOnly) {
		isReadOnly = readOnly;
	}

	public void handle(Socket sock) throws IOException {
		InputStream rawIn = new BufferedInputStream(sock.getInputStream());
		OutputStream rawOut = new BufferedOutputStream(sock.getOutputStream());

		String cmd = new PacketLineIn(rawIn).readStringRaw();
		final int nul = cmd.indexOf('\0');
		if (nul >= 0) {
			// Newer clients hide a "host" header behind this byte.
			// Currently we don't use it for anything, so we ignore
			// this portion of the command.
			cmd = cmd.substring(0, nul);
		}

		System.out.println("x händle request:"+cmd);

		String req[] = cmd.split(" ");
		String command = req[0].startsWith("git-") ? req[0] : "git-" + req[0];
		String reposName = req[1];

		System.out.print("händle:"+command);
		System.out.println(" for:"+reposName);

		if ("git-upload-pack".equals(command)) {
			// the client want to have missing objects
			Repository db = getRepository(reposName);
			final UploadPack rp = new UploadPack(db);
			//rp.setTimeout(Daemon.this.getTimeout());
			rp.upload(rawIn, rawOut, null);
		} else if ("git-receive-pack".equals(command)) {
			// the client send us new objects
			if (isReadOnly) return;
			Repository db = getRepository(reposName);
			final ReceivePack rp = new ReceivePack(db);
			final String name = "anonymous";
			final String email = name + "@freenet";
			rp.setRefLogIdent(new PersonIdent(name, email));
			//rp.setTimeout(Daemon.this.getTimeout());
			rp.receive(rawIn, rawOut, null);
		} else {
			System.err.println("Unknown command: "+command);
		}

		System.out.println("x händle request: pfertsch");

	}

	private Repository getRepository(String reposname) throws IOException {
		Repository db;
		File path = new File("gitcache/test").getCanonicalFile();
		db = new Repository(path);
		return db;
	}

}
