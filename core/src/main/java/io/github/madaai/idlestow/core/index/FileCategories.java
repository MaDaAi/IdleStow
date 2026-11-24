package io.github.madaai.idlestow.core.index;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Maps file extensions to coarse categories used by analytics, filters and default compression expectations. */
public final class FileCategories {
    public static final String DOCUMENTS = "Documents";
    public static final String IMAGES = "Images";
    public static final String AUDIO = "Audio";
    public static final String VIDEO = "Video";
    public static final String ARCHIVES = "Archives";
    public static final String CODE = "Code";
    public static final String DATA = "Data";
    public static final String EXECUTABLES = "Executables";
    public static final String OTHER = "Other";
    public static final String FOLDER = "Folder";

    private static final Map<String, String> BY_EXT = new HashMap<>();

    /** Formats that are usually already compressed. Sampling decides; this is only a prior. */
    public static final Set<String> USUALLY_COMPRESSED = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "avif", "mp3", "aac", "m4a", "ogg", "opus", "flac",
            "mp4", "mkv", "mov", "avi", "webm", "zip", "rar", "7z", "gz", "bz2", "xz", "zst", "tgz",
            "pdf", "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "jar", "apk", "cab", "msi");

    static {
        put(DOCUMENTS, "txt", "md", "rtf", "doc", "docx", "odt", "pdf", "xls", "xlsx", "ods", "ppt", "pptx", "odp",
                "csv", "tsv", "log", "tex", "epub", "html", "htm");
        put(IMAGES, "jpg", "jpeg", "png", "gif", "bmp", "tif", "tiff", "webp", "heic", "avif", "svg", "psd", "raw",
                "cr2", "nef", "dng", "ico");
        put(AUDIO, "mp3", "wav", "aac", "m4a", "ogg", "opus", "flac", "wma", "aiff");
        put(VIDEO, "mp4", "mkv", "mov", "avi", "webm", "wmv", "m4v", "mpg", "mpeg");
        put(ARCHIVES, "zip", "rar", "7z", "gz", "bz2", "xz", "zst", "tar", "tgz", "cab", "iso");
        put(CODE, "java", "py", "js", "ts", "c", "cpp", "h", "hpp", "cs", "go", "rs", "kt", "rb", "php", "swift",
                "scala", "sql", "sh", "ps1", "json", "xml", "yaml", "yml", "toml", "ini", "css", "ipynb");
        put(DATA, "db", "sqlite", "sqlite3", "mdb", "accdb", "dat", "bin", "parquet", "npy", "h5", "pst", "ost",
                "vhd", "vhdx", "vmdk", "vdi", "qcow2", "kdbx");
        put(EXECUTABLES, "exe", "dll", "sys", "msi", "com", "scr", "drv", "ocx", "so", "dylib", "app", "bat", "cmd",
                "jar", "apk");
    }

    private FileCategories() { }

    private static void put(String cat, String... exts) {
        for (String e : exts) BY_EXT.putIfAbsent(e, cat);
    }

    public static String of(String extension, boolean directory) {
        if (directory) return FOLDER;
        return BY_EXT.getOrDefault(extension == null ? "" : extension, OTHER);
    }
}
