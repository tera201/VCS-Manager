package org.tera201.vcsmanager.scm;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.tera201.vcsmanager.RepoDrillerException;
import org.tera201.vcsmanager.util.DataBaseUtil;
import org.tera201.vcsmanager.util.RDFileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A GitRepository that knows how to clone a remote repo and clean up after itself.
 * Instantiating a GitRemoteRepository will clone the specified repo, which
 *  - is expensive
 *  - may throw an exception
 *
 */
/* TODO Requiring cloning at instantiation-time is not "lightweight".
 *      It means the user won't get any results until after cloning every repo being analyzed.
 *      I suggest we only actually clone when the clone() method of an SCM is invoked.
 *      We should override GitRepository's cheap "copy" implementation of clone() and actually clone then.
 *      In this case I suppose we might want to differentiate between a "lightweight" SCM and a "full" SCM? Hmm.
 *      */
public class GitRemoteRepository extends GitRepository {

	/* Constants. */
	public static final String URL_SUFFIX = ".git";

	/* Internal. */
	private boolean hasLocalState = false;

	/* User-defined. */
	private String uri;
	private String repoName;

	private String username;
	private String password;
	private Path path; /* TODO GitRepository also has a path member. Make it protected and inherit, or use getter/setter as needed? */
	private boolean bareClone = false;

	private static Logger log = LoggerFactory.getLogger(GitRemoteRepository.class);

	/**
	 * @param uri	Where do we clone the repo from?
	 * @throws GitAPIException
	 * @throws IOException
	 */

	public GitRemoteRepository(String uri, String destination, boolean bare) {
		this(uri, destination, bare, null, null, null);
	}
	public GitRemoteRepository(String uri) {
		this(uri, null, false);
	}
	public GitRemoteRepository(String uri, String destination) {
		this(uri, destination, null);
	}
	public GitRemoteRepository(String uri, String destination, DataBaseUtil dataBaseUtil) {
		super();
		try {
			this.uri = uri;
			this.repoName = repoNameFromURI(uri);
			this.dataBaseUtil = dataBaseUtil;
			path = Paths.get(destination + "/" + repoName);
			dataBaseUtil.insertProject(repoName, path.toString());
			Integer projectId = dataBaseUtil.getProjectId(repoName, path.toString());
			this.projectId = Objects.requireNonNullElseGet(projectId, () -> dataBaseUtil.insertProject(repoName, path.toString()));
			hasLocalState = true;
		} catch (RepoDrillerException e) {
			log.error("Unsuccessful git remote repository initialization", e);
			throw new RepoDrillerException(e);
		}
		this.setRepoName(repoName);
		this.setPath(path.toString());
		this.setFirstParentOnly(true); /* TODO. */
	}

	/**
	 * @param uri	Where do we clone the repo from? Anything that works as an argument to "git clone", e.g. local dir or GitHub address.
	 * @param destination	If provided, clone here. Should not exist already.
	 *                   	If null, clones to a unique temp dir.
	 * @param bare	Bare clone (metadata only) or full?
	 */
	public GitRemoteRepository(String uri, String destination, boolean bare, String username, String password, DataBaseUtil dataBaseUtil) {
		super();

		try {
			/* Set members. */
			this.uri = uri;
			this.bareClone = bare;
			this.repoName = repoNameFromURI(uri);
			this.username = username;
			this.password = password;
			this.dataBaseUtil = dataBaseUtil;

			/* Choose our own path? */
			if (destination == null) {
				/* Pick a temp dir name. */
				String tempDirPath;
				tempDirPath = RDFileUtils.getTempPath(null);
				path = Paths.get(tempDirPath + "-" + repoName); // foo-RepoOne
			}
			else
				path = Paths.get(destination + "/" + repoName);

			/* path must not exist already. */
			if (RDFileUtils.exists(path)) {
				throw new RepoDrillerException("Error, path " + path + " already exists");
			}

			/* Clone the remote repo. */
			cloneGitRepository(uri, path, bare);
			Integer projectId = dataBaseUtil.getProjectId(repoName, path.toString());
            this.projectId = Objects.requireNonNullElseGet(projectId, () -> dataBaseUtil.insertProject(repoName, path.toString()));
			hasLocalState = true;
		} catch (IOException|GitAPIException|RepoDrillerException e) {
			log.error("Unsuccessful git remote repository initialization", e);
			throw new RepoDrillerException(e);
		}

		log.info("url " + uri + " destination " + destination + " bare " + bare + " (path " + path + ")");

		/* Fill in GitRepository details. */
		this.setRepoName(repoName);
		this.setPath(path.toString());
		this.setFirstParentOnly(true); /* TODO. */
	}

	/**
	 * Clone a git repository.
	 *
	 * @param uri	Where from?
	 * @param dest	Where to?
	 * @param bare	Bare (metadata-only) or full?
	 * @throws GitAPIException
	 */
	private void cloneGitRepository(String uri, Path dest, boolean bare) throws GitAPIException {
		File directory = new File(dest.toString());
		CredentialsProvider credentialsProvider = null;

		if (directory.exists())
			throw new RepoDrillerException("Error, destination " + dest.toString() + " already exists");
		if (username != null)
			credentialsProvider = new UsernamePasswordCredentialsProvider(username, password);
		log.info("Cloning Remote Repository " + uri + " into " + this.path);
		Git.cloneRepository()
				.setURI(uri)
				.setBare(bare)
				.setDirectory(directory)
				.setCredentialsProvider(credentialsProvider)
				.setCloneAllBranches(true)
				.setNoCheckout(false)
				.call();
	}

	/**
	 * Extract a git repo name from its URL.
	 *
	 * @param uri
	 * @return
	 */
	public static String repoNameFromURI(String uri) {
		/* Examples:
		 *   git@github.com:substack/node-mkdirp.git
		 *   https://bitbucket.org/fenics-project/notebooks.git
		 *   /tmp/existing-git-dir
		 */
		int lastSlashIx = uri.lastIndexOf("/");

		int lastSuffIx = uri.lastIndexOf(URL_SUFFIX);
		if (lastSuffIx < 0)
			lastSuffIx = uri.length();

		if (lastSlashIx < 0 || lastSuffIx <= lastSlashIx)
			throw new RepoDrillerException("Error, ill-formed url: " + uri);

		return uri.substring(lastSlashIx + 1, lastSuffIx);
	}

	/* Various factory methods. */

	public static SCMRepository singleProject(String url) {
		return singleProject(url, null, false, null, null, null);
	}

	@SuppressWarnings("resource")
	public static SCMRepository singleProject(String url, String rootPath, boolean bare) {
		return new GitRemoteRepository(url, rootPath, bare, null, null, null).info();
	}

	@SuppressWarnings("resource")
	public static SCMRepository singleProject(String url, String rootPath, boolean bare, String username, String password, DataBaseUtil dataBaseUtil) {
		return new GitRemoteRepository(url, rootPath, bare, username, password, dataBaseUtil).info();
	}

	@SuppressWarnings("resource")
	public static SCMRepository getSingleProject(String url, String rootPath, DataBaseUtil dataBaseUtil) {
		return new GitRemoteRepository(url, rootPath, dataBaseUtil).getInfo();
	}

	@SuppressWarnings("resource")
	public static SCMRepository getSingleProject(String projectPath, DataBaseUtil dataBaseUtil) {
		return new GitRepository(projectPath, true, dataBaseUtil).getInfo();
	}

	public static SCMRepository[] allProjectsIn(List<String> urls) throws GitAPIException, IOException {
		return allProjectsIn(urls, null, false);
	}

	protected static SCMRepository[] allProjectsIn(List<String> urls, String rootPath, boolean bare) {
		List<SCMRepository> repos = new ArrayList<SCMRepository>();
		for (String url : urls) {
			repos.add(singleProject(url, rootPath, bare));
		}

		return repos.toArray(new SCMRepository[repos.size()]);
	}

	public static SingleGitRemoteRepositoryBuilder hostedOn(String gitUrl) {
		return new SingleGitRemoteRepositoryBuilder(gitUrl);
	}

	public static MultipleGitRemoteRepositoryBuilder hostedOn(List<String> gitUrls) {
		return new MultipleGitRemoteRepositoryBuilder(gitUrls);
	}

	@Override
	public SCM clone(Path dest) {
		try {
			log.info("Cloning " + uri + " to " + dest);
			cloneGitRepository(uri, dest, bareClone);
			return new GitRepository(dest.toString());
		} catch (GitAPIException e) {
			throw new RepoDrillerException("Clone failed: " + e);
		}
	}

	@Override
	public void delete() {
		if (hasLocalState) {
			try {
				FileUtils.deleteDirectory(new File(path.toString()));
				hasLocalState = false;
			} catch (IOException e) {
				log.error("Couldn't delete GitRemoteRepository with path " + path);
				log.error(e.getMessage());
			}
		}
	}

	public String getRepositoryPath() {
		return path.toString();
	}
}
