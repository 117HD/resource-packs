package ci

enum class Labels(val color: String, val displayName: String) {
    ADDED("28a745", "added"),           // Green
    CHANGED("007bff", "changed"),          // Blue
    RENAMED("ffc107", "renamed"),          // Yellow/Orange
    REMOVED("dc3545", "removed"),          // Red
    NOT_KNOWN("6c757d", "not-known"),        // Gray
    AUTHOR_CHANGED("6f42c1", "author-changed"),           // Purple
    SIZE_XS("e3f2fd", "size-xs"),          // Light Blue
    SIZE_S("bbdefb", "size-s"),           // Light Blue
    SIZE_M("90caf9", "size-m"),           // Medium Blue
    SIZE_L("64b5f6", "size-l"),           // Blue
    SIZE_XL("1976d2", "size-xl");           // Dark Blue
    
    val labelName: String get() = displayName
}