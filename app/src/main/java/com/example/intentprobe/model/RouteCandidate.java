package com.example.intentprobe.model;

public class RouteCandidate {
    public long id;
    public String pkg;
    public String activity;
    public String alias;
    public boolean exported;
    public boolean exportedInferred;
    public boolean componentEnabled = true;
    public String launchMode;
    public String permission;
    public String action;
    public String categories;
    public String scheme;
    public String host;
    public String port;
    public String path;
    public String pathPrefix;
    public String pathPattern;
    public String pathSuffix;
    public String mimeType;
    public String uri;
    public boolean patternOnly;
    public int filterIndex;
    /** "manifest" for intent-filter entries, "dex" for string constants in code. */
    public String origin = "manifest";

    public String simpleActivity() {
        if (activity == null) return "";
        int dot = activity.lastIndexOf('.');
        return dot >= 0 ? activity.substring(dot + 1) : activity;
    }
}
