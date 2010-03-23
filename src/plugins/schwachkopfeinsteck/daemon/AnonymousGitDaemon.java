package plugins.schwachkopfeinsteck.daemon;

import plugins.schwachkopfeinsteck.RepositoryManager;

import freenet.support.incubation.server.AbstractServer;
import freenet.support.incubation.server.AbstractService;
import freenet.support.plugins.helpers1.PluginContext;

public class AnonymousGitDaemon extends AbstractServer {

	private boolean isReadOnly = true;
	private final PluginContext pluginContext;
	private final RepositoryManager repositoryManager;

	public AnonymousGitDaemon(String servername, RepositoryManager repositorymanager, PluginContext plugincontext) {
		super(servername, plugincontext.node.executor);
		pluginContext = plugincontext;
		repositoryManager = repositorymanager;
	}

	@Override
	protected AbstractService getService() {
		return new AnonymousGitService(isReadOnly(), eXecutor, pluginContext, repositoryManager);
	}

	public void setReadOnly(boolean readOnly) {
		isReadOnly = readOnly;
	}

	public boolean isReadOnly() {
		return isReadOnly;
	}

}
