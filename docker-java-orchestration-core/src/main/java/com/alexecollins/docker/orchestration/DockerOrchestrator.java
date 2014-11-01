package com.alexecollins.docker.orchestration;


import com.alexecollins.docker.orchestration.model.*;
import com.alexecollins.docker.orchestration.plugin.api.Plugin;
import com.alexecollins.docker.orchestration.util.Pinger;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.DockerException;
import com.github.dockerjava.api.NotFoundException;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.api.model.Link;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;

import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.apache.commons.io.IOUtils.copyLarge;

/**
 * Orchestrates multiple Docker containers based on
 */
public class DockerOrchestrator {
	public static final String DEFAULT_HOST = "http://127.0.0.1:2375";
	public static final FileFilter DEFAULT_FILTER = new FileFilter() {
		@Override
		public boolean accept(File pathname) {
			return false;
		}
	};
	public static final Properties DEFAULT_PROPERTIES = new Properties();

	private static final int snooze = 0;

	private final Logger logger;
	private final DockerClient docker;
	private final Repo repo;

    private final FileOrchestrator fileOrchestrator;
	private final Set<BuildFlag> buildFlags;
    private final List<Plugin> plugins = new ArrayList<Plugin>();

    public DockerOrchestrator(DockerClient docker, File src, File workDir, File rootDir, String prefix, FileFilter filter, Properties properties) {
        this(docker, new Repo(docker, prefix, src, properties), new FileOrchestrator(workDir, rootDir, filter, properties), EnumSet.noneOf(BuildFlag.class), null);
    }

	public DockerOrchestrator(DockerClient docker, File src, File workDir, File rootDir, String prefix, FileFilter filter, Properties properties, Set<BuildFlag> buildFlags) {
        this(docker, new Repo(docker, prefix, src, properties), new FileOrchestrator(workDir, rootDir, filter, properties), buildFlags, LoggerFactory.getLogger(DockerOrchestrator.class));
	}

	DockerOrchestrator(DockerClient docker, Repo repo, FileOrchestrator fileOrchestrator, Logger logger) {
		this(docker,repo, fileOrchestrator, EnumSet.noneOf(BuildFlag.class), logger);
	}

    private DockerOrchestrator(DockerClient docker, Repo repo, FileOrchestrator fileOrchestrator, Set<BuildFlag> buildFlags, Logger logger) {
	    if (docker == null) {
            throw new IllegalArgumentException("docker is null");
        }
        if (repo == null) {
            throw new IllegalArgumentException("repo is null");
        }
	    if (buildFlags == null) {throw new IllegalArgumentException("buildFlags is null");}


        this.docker = docker;
        this.repo = repo;
        this.fileOrchestrator = fileOrchestrator;

	    this.buildFlags = buildFlags;
        this.logger = logger;

        for (Plugin plugin : ServiceLoader.load(Plugin.class)) {
            plugins.add(plugin);
            logger.info("loaded " + plugin.getClass() + " plugin");
        }
    }

	public void clean() {
		for (Id id : repo.ids(true)) {
			stop(id);
			clean(id);
		}
	}

	void clean(final Id id) {
		if (id == null) {
			throw new IllegalArgumentException("id is null");
		}
		stop(id);
		logger.info("Clean " + id);
		for (Container container : repo.findContainers(id, true)) {
			logger.info("Removing container " + container.getId());
			try {
				docker.removeContainerCmd(container.getId()).withForce().exec();
			} catch (DockerException e) {
				throw new OrchestrationException(e);
			}
		}
		String imageId = null;
		try {
            imageId = repo.findImageId(id);
        } catch (NotFoundException e) {
			logger.warn("Image " + id + " not found");
		} catch (DockerException e) {
			throw new OrchestrationException(e);
		}
		if (imageId != null) {
            logger.info("Removing image " + imageId);
            try {
                docker.removeImageCmd(imageId).withForce().exec();
            } catch (DockerException e) {
				logger.warn(e.getMessage());
			}
		}
		snooze();
	}

	void build(final Id id) {
		if (id == null) {
			throw new IllegalArgumentException("id is null");
		}
		logger.info("Package " + id);
		try {
			build(prepare(id), id);
		} catch (IOException e) {
			throw new OrchestrationException(e);
		}

		snooze();
	}

	private void snooze() {
        if (snooze == 0) {
            return;
        }
        logger.info("Snoozing for " + snooze + "ms");
		try {
			Thread.sleep(snooze);
		} catch (InterruptedException e) {
			throw new OrchestrationException(e);
		}
	}

    private File prepare(Id id) throws IOException {
        if (id == null) {
			throw new IllegalArgumentException("id is null");
		}
        return fileOrchestrator.prepare(id, repo.src(id), repo.conf(id));
    }




	@SuppressWarnings(("DM_DEFAULT_ENCODING"))
	private void build(File dockerFolder, Id id) {

		InputStream in;
		try {
            BuildImageCmd build = docker.buildImageCmd(dockerFolder);
            for(BuildFlag f : buildFlags){
                switch (f){
                    case NO_CACHE: build.withNoCache();break;
                    case REMOVE_INTERMEDIATE_IMAGES: build.withRemove(true);break;
                }
            }
            build.withTag(repo.tag(id));
            in = build.exec();
		} catch (DockerException e) {
			throw new OrchestrationException(e);
		}

		final StringWriter out = new StringWriter();
		try {
			copyLarge(new InputStreamReader(in, Charset.defaultCharset()), out);
		} catch (IOException e) {
			throw new OrchestrationException(e);
		} finally {
			closeQuietly(in);
		}

		String log = out.toString();
		if (!log.contains("Successfully built")) {
			throw new IllegalStateException("failed to build, log missing lines in" + log);
		}

		snooze();
	}


    private void start(final Id id) {
        if (id == null) {
            throw new IllegalArgumentException("id is null");
        }

        try {
            Container existingContainer = repo.findContainer(id);

            if (existingContainer == null) {
                logger.info("No existing container so creating and starting new one");
                String containerId = createNewContainer(id);
                startContainer(containerId, id);

            } else if (!isImageIdFromContainerMatchingProvidedImageId(existingContainer.getId(), id)) {
                logger.info("Image IDs do not match, removing container and creating new one from image");
                docker.removeContainerCmd(existingContainer.getId()).exec();
                startContainer(createNewContainer(id), id);

            } else if(isRunning(id)) {
                logger.info("Container " + id + " already running");

            } else {
                logger.info("Starting existing container " + existingContainer.getId());
                startContainer(existingContainer.getId(), id);
            }

        } catch (DockerException e) {
            throw new OrchestrationException(e);
        }
	    snooze();
	    healthCheck(id);
    }

    private boolean isImageIdFromContainerMatchingProvidedImageId(String containerId, final Id id) {
        try {
            String containerImageId = lookupImageIdFromContainer(containerId);
            String imageId = repo.findImageId(id);
            return containerImageId.equals(imageId);
        } catch (DockerException e) {
            logger.error("Unable to find image with id " + id, e);
            throw new OrchestrationException(e);
        }

    }

    private String lookupImageIdFromContainer(String containerId) {
        try {
            InspectContainerResponse containerInspectResponse = docker.inspectContainerCmd(containerId).exec();
            return containerInspectResponse.getImageId();
        } catch (DockerException e) {
            logger.error("Unable to inspect container " + containerId, e);
            throw new OrchestrationException(e);
        }
    }

    private void startContainer(String idOfContainerToStart, final Id id) {
        try {
            logger.info("Starting " + id);
            StartContainerCmd start = docker.startContainerCmd(idOfContainerToStart);

            prepareHostConfig(id, start);
            start.exec();

            for (Plugin plugin : plugins) {
                plugin.started(id.toString());
            }

        } catch (DockerException e) {
            logger.error("Unable to start container " + idOfContainerToStart, e);
            throw new OrchestrationException(e);
        }
    }


    private String createNewContainer(Id id) throws DockerException {
        logger.info("Creating " + id);
        Conf conf = repo.conf(id);
        CreateContainerCmd createCmd = docker.createContainerCmd(repo.findImageId(id));
        createCmd.withName(repo.containerName(id));
        logger.info(" - env " + conf.getEnv());
        createCmd.withEnv(asEnvList(conf.getEnv()));
        CreateContainerResponse response = createCmd.exec();
		snooze();
        return response.getId();
	}

    /**
     * Converts String to String map to list of
     * key=value strings.
     * @param env
     * @return
     */
    private String[] asEnvList(Map<String, String> env) {
        ArrayList<String> list = new ArrayList<String>();
        for(Map.Entry<String,String> entry : env.entrySet()){
            list.add(entry.getKey()+"="+entry.getValue());
        }
        return list.toArray(new String[list.size()]);
    }


    private boolean isRunning(Id id) {
		if (id == null) {throw new IllegalArgumentException("id is null");}
		boolean running = false;
        final Container candidate = repo.findContainer(id);
		for (Container container : docker.listContainersCmd().withShowAll(false).exec()) {
			running |= candidate != null && candidate.getId().equals(container.getId());
		}
		return running;
	}

	private void healthCheck(Id id) {
		final HealthChecks healthChecks = repo.conf(id).getHealthChecks();
		for (Ping ping : healthChecks.getPings()) {
			logger.info("Pinging " + ping.getUrl());
			if (!Pinger.ping(ping.getUrl(), ping.getTimeout())) {
				throw new OrchestrationException("timeout waiting for " + ping.getUrl() + " for " + ping.getTimeout());
			}
		}
	}

	private void prepareHostConfig(Id id, StartContainerCmd config) {
		config.withPublishAllPorts(true);

        Link[] links = links(id);
        logger.info(" - links " + repo.conf(id).getLinks());
        config.withLinks(links);

		final Ports portBindings = new Ports();
		for (String e : repo.conf(id).getPorts()) {

			final String[] split = e.split(" ");

			assert split.length == 1 || split.length == 2;

			final int a = Integer.parseInt(split[0]);
			final int b = split.length == 2 ? Integer.parseInt(split[1]) : a;

			logger.info(" - port " + e);
            portBindings.bind(new ExposedPort(a, InternetProtocol.TCP), new Ports.Binding(b));
        }
        config.withPortBindings(portBindings);

        logger.info(" - volumes " + repo.conf(id).getVolumes());

        final List<Bind> binds = new ArrayList<Bind>();
        for (Map.Entry<String,String> entry : repo.conf(id).getVolumes().entrySet()) {
            String volumePath = entry.getKey();
            String hostPath = entry.getValue();
            File file = new File(hostPath);
            String path = file.getAbsolutePath();
            logger.info(" - volumes " + volumePath + " <- " + path);
            binds.add(new Bind(path, new Volume(volumePath)));
        }

		config.withBinds(binds.toArray(new Bind[binds.size()]));
	}

	private Link[] links(Id id) {
        final List<com.alexecollins.docker.orchestration.model.Link> links = repo.conf(id).getLinks();
        final Link[] out = new Link[links.size()];
		for (int i = 0; i < links.size(); i++) {
            com.alexecollins.docker.orchestration.model.Link link = links.get(i);
            final String name = com.alexecollins.docker.orchestration.util.Links.name(repo.findContainer(link.getId()).getNames());
            final String alias = link.getAlias();
            out[i] = new Link(name, alias);
        }
		return out;
	}

	private void stop(final Id id) {
		if (id == null) {
			throw new IllegalArgumentException("id is null");
		}
		for (Container container : repo.findContainers(id, false)) {
			logger.info("Stopping " + Arrays.toString(container.getNames()));
			try {
				docker.stopContainerCmd(container.getId()).withTimeout(1).exec();
			} catch (DockerException e) {
				throw new OrchestrationException(e);
			}
			snooze();
		}
	}

	public void build() {
		for (Id id : ids()) {
			build(id);
		}
	}

	public void start() {
		for (Id id : ids()) {
			try {
				if (!repo.imageExists(id)) {
					build(id);
				}
			} catch (DockerException e) {
				throw new OrchestrationException(e);
			}
			start(id);
		}
	}

	public void stop() {
		for (Id id : repo.ids(true)) {
			stop(id);
		}
	}

	public List<Id> ids() {
		return repo.ids(false);
	}

	public void push() {
		for (Id id : ids()) {
			push(id);
		}
	}

	private void push(Id id) {
		try {
            docker.pushImageCmd(repo.imageName(id)).withAuthConfig(docker.authConfig()).exec();
        } catch (DockerException e) {
			throw new OrchestrationException(e);
		}
		snooze();
	}

	public boolean isRunning() {
		for (Id id : ids()) {
			if (!isRunning(id)) {
				return false;
			}
		}
		return true;
	}

    <P extends Plugin> P getPlugin(Class<P> pluginClass) {
        for (Plugin plugin : plugins) {
            if (plugin.getClass().equals(pluginClass)) {
                return (P)plugin;
            }
        }
        throw new NoSuchElementException("unabled to find plugin " + pluginClass);
    }
}