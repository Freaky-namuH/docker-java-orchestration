package com.alexecollins.docker.orchestration;

import com.alexecollins.docker.orchestration.model.Conf;
import com.alexecollins.docker.orchestration.model.ContainerConf;
import com.alexecollins.docker.orchestration.model.Id;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.DockerException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Image;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static java.util.Arrays.asList;

@SuppressWarnings("CanBeFinal")
class Repo {

    private static final Logger LOG = LoggerFactory.getLogger(Repo.class);

    private static ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());
    private final DockerClient docker;
    private final String user;
    private final String project;
    private final File src;
    private final Map<Id, Conf> confs = new LinkedHashMap<>();

    /**
     * @param user Name of the repo use. Maybe null.
     */
    @SuppressWarnings("ConstantConditions")
    Repo(DockerClient docker, String user, String project, File src, Properties properties) {
        if (docker == null) {
            throw new IllegalArgumentException("docker is null");
        }
        if (user == null) {
            throw new IllegalArgumentException("user is null");
        }
        if (project == null) {
            throw new IllegalArgumentException("project is null");
        }
        if (src == null) {
            throw new IllegalArgumentException("src is null");
        }
        if (!src.isDirectory()) {
            throw new IllegalArgumentException("src " + src + " does not exist or is directory");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties is null");
        }

        this.user = user;
        this.docker = docker;
        this.project = project;
        this.src = src;

        if (src.isDirectory()) {
            readDockerConf(src, properties);
            ensureEmptyFolderConfs(src);
            readChildConfs(src, properties);
        }
    }

    private static Conf readConfFile(File confFile, Properties properties) throws IOException {
        return confFile.length() > 0 ? MAPPER.readValue(Confs.replacingReader(confFile, properties), Conf.class) : new Conf();
    }

    private void readDockerConf(File src, Properties properties) {
        // prioritise the docker.yml, especially for ordering
        File dockerConf = new File(src, "docker.yml");
        if (dockerConf.exists()) {
            LOG.info("reading " + dockerConf);
            try {
                confs.putAll(Confs.read(dockerConf, properties));
            } catch (IOException e) {
                throw new OrchestrationException(e);
            }
        }
    }

    private void readChildConfs(File src, Properties properties) {
        for (Id id : confs.keySet()) {
            File confFile = new File(src, id + "/conf.yml");
            if (confFile.exists()) {
                LOG.info("reading " + confFile);
                try {
                    confs.put(id, readConfFile(confFile, properties));
                } catch (IOException e) {
                    throw new OrchestrationException(e);
                }
            }
        }
    }

    private void ensureEmptyFolderConfs(File src) {
        for (File file : src.listFiles((FileFilter) DirectoryFileFilter.INSTANCE)) {
            Id id = new Id(file.getName());
            if (!confs.containsKey(id)) {
                confs.put(id, new Conf());
            }
        }
    }

    public String tag(Id id) {
        Conf conf = conf(id);
        return
                conf.hasTag()
                        ? conf.getTag()
                        : imageName(id);
    }

    private String imageName(Id id) {
        return user + "/" + project + "_" + id;
    }

    String containerName(Id id) {
        ContainerConf container = confs.get(id).getContainer();
        return container.hasName() ? container.getName() : defaultContainerName(id);
    }

    private String defaultContainerName(Id id) {
        return "/" + project + "_" + id;
    }

    List<Container> findContainers(Id id, boolean allContainers) {
        final List<Container> strings = new ArrayList<>();
        for (Container container : docker.listContainersCmd().withShowAll(allContainers).exec()) {
            if (container.getImage().equals(imageName(id)) || asList(container.getNames()).contains(containerName(id))) {
                strings.add(container);
            }
        }
        return strings;
    }

    public Container findContainer(Id id) {
        final List<Container> containerIds = findContainers(id, true);
        return containerIds.isEmpty() ? null : containerIds.get(0);
    }


    public String findImageId(Id id) {
        String imageTag = tag(id);
        LOG.debug("Converting {} ({}) to image id.", id, imageTag);
        List<Image> images = docker.listImagesCmd().exec();
        for (Image i : images) {
            for (String tag : i.getRepoTags()) {
                if (tag.startsWith(imageTag)) {
                    LOG.debug("Using {} ({}) for {}. It matches (enough) to {}.", new Object[]{
                            i.getId(),
                            tag,
                            id.toString(),
                            imageTag});
                    return i.getId();
                }
            }
        }
        LOG.debug("could not find image ID for \"" + id + "\" (tag \"" + imageTag + "\")");
        return null;
    }

    boolean imageExists(Id id) throws DockerException {
        return findImageId(id) != null;
    }

    private File src() {
        return src;
    }


    File src(Id id) {
        return new File(src(), id.toString());
    }

    List<Id> ids(boolean reverse) {

        final List<Id> in = new LinkedList<>(confs.keySet());

        // keep in order
        final Map<Id, List<Id>> links = new LinkedHashMap<>();
        for (Id id : in) {
            links.put(id, com.alexecollins.docker.orchestration.util.Links.ids(confs.get(id).getLinks()));
        }

        final List<Id> out = sort(links);

        if (reverse) {
            Collections.reverse(out);
        }

        return out;
    }

    List<Id> sort(final Map<Id, List<Id>> links) {
        final List<Id> in = new LinkedList<>(links.keySet());
        final List<Id> out = new LinkedList<>();

        while (!in.isEmpty()) {
            boolean hit = false;
            for (Iterator<Id> iterator = in.iterator(); iterator.hasNext(); ) {
                final Id id = iterator.next();
                if (out.containsAll(links.get(id))) {
                    out.add(id);
                    iterator.remove();
                    hit = true;
                }
            }
            if (!hit) {
                throw new IllegalStateException("dependency error (e.g. circular dependency) amongst " + in);
            }
        }

        return out;
    }

    Conf conf(Id id) {
        return confs.get(id);
    }
}
