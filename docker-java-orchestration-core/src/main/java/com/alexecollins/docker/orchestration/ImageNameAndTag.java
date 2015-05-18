package com.alexecollins.docker.orchestration;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImageNameAndTag {
    private static Pattern pattern = Pattern.compile("(.*)(:)([\\w][\\w.-]{0,127})$");
    private final String name;
    private final String tag;

    public ImageNameAndTag(String fullImageName) {
        Matcher matcher = pattern.matcher(fullImageName);
        if (matcher.find()) {
            this.name = matcher.group(1);
            this.tag = matcher.group(3);
        } else {
            this.name = fullImageName;
            this.tag = null;
        }
    }

    public boolean hasTag() {
        return tag != null;
    }

    public String getName() {
        return name;
    }

    public String getTag() {
        return tag;
    }

    @Override
    public String toString() {
        return name + (hasTag() ? ":" + tag : "");
    }
}
