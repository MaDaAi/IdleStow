package io.github.madaai.idlestow.app.ui;

/** Top level destinations in sidebar order (Ctrl+1 .. Ctrl+7). */
public enum ViewId {
    OVERVIEW("Overview", Icons.OVERVIEW),
    EXPLORER("Explorer", Icons.EXPLORER),
    RECOMMENDATIONS("Recommendations", Icons.RECOMMEND),
    JOBS("Jobs", Icons.JOBS),
    HISTORY("History", Icons.HISTORY),
    RECOVERY("Recovery", Icons.RECOVERY),
    SETTINGS("Settings", Icons.SETTINGS);

    private final String title;
    private final Icons icon;

    ViewId(String title, Icons icon) {
        this.title = title;
        this.icon = icon;
    }

    public String title() {
        return title;
    }

    public Icons icon() {
        return icon;
    }

    public static ViewId parse(String s, ViewId dflt) {
        if (s == null) return dflt;
        try {
            return valueOf(s);
        } catch (IllegalArgumentException e) {
            return dflt;
        }
    }
}
