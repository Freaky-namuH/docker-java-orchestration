package com.alexecollins.docker.orchestration;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;

public class ImageNameAndTagTest {
    //[REGISTRYHOST/][USERNAME/]NAME[:TAG]

    @Test
    public void shouldExtractTagWhen_RegistryHost_Port_Username_Name_Tag() {

        String image = "docker.local:5000/username/thename:tag-1.2";

        ImageNameAndTag imageNameAndTag = new ImageNameAndTag(image);
        assertThat(imageNameAndTag.getName(), equalTo("docker.local:5000/username/thename"));
        assertThat(imageNameAndTag.hasTag(), is(true));
        assertThat(imageNameAndTag.getTag(), equalTo("tag-1.2"));
    }

    @Test
    public void shouldHaveNoTagWhen_RegistryHost_Port_Username_Name() {

        String image = "docker.local:5000/username/thename";

        ImageNameAndTag imageNameAndTag = new ImageNameAndTag(image);
        assertThat(imageNameAndTag.getName(), equalTo("docker.local:5000/username/thename"));
        assertHasNoTag(imageNameAndTag);
    }


    @Test
    public void shouldExtractTagWhen_RegistryHost_Port_Name_Tag() {

        String image = "docker.local:5000/thename:tag-1.2";

        ImageNameAndTag imageNameAndTag = new ImageNameAndTag(image);
        assertThat(imageNameAndTag.getName(), equalTo("docker.local:5000/thename"));
        assertThat(imageNameAndTag.hasTag(), is(true));
        assertThat(imageNameAndTag.getTag(), equalTo("tag-1.2"));
    }

    @Test
    public void shouldHaveNoTagWhen_RegistryHost_Port_Name() {

        String image = "docker.local:5000/thename";

        ImageNameAndTag imageNameAndTag = new ImageNameAndTag(image);
        assertThat(imageNameAndTag.getName(), equalTo("docker.local:5000/thename"));
        assertHasNoTag(imageNameAndTag);
    }


    //#####
    @Test
    public void shouldExtractTagWhen_RegistryHost_Username_Name_Tag() {

        String image = "docker.local/username/thename:tag-1.2";

        ImageNameAndTag imageNameAndTag = new ImageNameAndTag(image);
        assertThat(imageNameAndTag.getName(), equalTo("docker.local/username/thename"));
        assertThat(imageNameAndTag.hasTag(), is(true));
        assertThat(imageNameAndTag.getTag(), equalTo("tag-1.2"));
    }

    @Test
    public void shouldHaveNoTagWhen_RegistryHost_Username_Name() {

        String image = "docker.local/username/thename";

        ImageNameAndTag imageNameAndTag = new ImageNameAndTag(image);
        assertThat(imageNameAndTag.getName(), equalTo("docker.local/username/thename"));
        assertHasNoTag(imageNameAndTag);
    }


    @Test
    public void shouldExtractTagWhen_RegistryHost_Name_Tag() {

        String image = "docker.local/thename:tag-1.2";

        ImageNameAndTag imageNameAndTag = new ImageNameAndTag(image);
        assertThat(imageNameAndTag.getName(), equalTo("docker.local/thename"));
        assertThat(imageNameAndTag.hasTag(), is(true));
        assertThat(imageNameAndTag.getTag(), equalTo("tag-1.2"));
    }

    @Test
    public void shouldHaveNoTagWhen_RegistryHost_Name() {

        String image = "docker.local/thename";

        ImageNameAndTag imageNameAndTag = new ImageNameAndTag(image);
        assertThat(imageNameAndTag.getName(), equalTo("docker.local/thename"));
        assertHasNoTag(imageNameAndTag);
    }
    //#####
    @Test
    public void shouldExtractTagWhen_Username_Name_Tag() {

        String image = "username/thename:tag-1.2";

        ImageNameAndTag imageNameAndTag = new ImageNameAndTag(image);
        assertThat(imageNameAndTag.getName(), equalTo("username/thename"));
        assertThat(imageNameAndTag.hasTag(), is(true));
        assertThat(imageNameAndTag.getTag(), equalTo("tag-1.2"));
    }

    @Test
    public void shouldHaveNoTagWhen_Username_Name() {

        String image = "username/thename";

        ImageNameAndTag imageNameAndTag = new ImageNameAndTag(image);
        assertThat(imageNameAndTag.getName(), equalTo("username/thename"));
        assertHasNoTag(imageNameAndTag);
    }


    @Test
    public void shouldExtractTagWhen_Name_Tag() {

        String image = "thename:tag-1.2";

        ImageNameAndTag imageNameAndTag = new ImageNameAndTag(image);
        assertThat(imageNameAndTag.getName(), equalTo("thename"));
        assertThat(imageNameAndTag.hasTag(), is(true));
        assertThat(imageNameAndTag.getTag(), equalTo("tag-1.2"));
    }

    @Test
    public void shouldHaveNoTagWhen_Name() {

        String image = "thename";

        ImageNameAndTag imageNameAndTag = new ImageNameAndTag(image);
        assertThat(imageNameAndTag.getName(), equalTo("thename"));
        assertHasNoTag(imageNameAndTag);
    }

    private void assertHasNoTag(ImageNameAndTag imageNameAndTag) {
        assertThat(imageNameAndTag.hasTag(), is(false));
        assertThat(imageNameAndTag.getTag(), nullValue());
    }


}