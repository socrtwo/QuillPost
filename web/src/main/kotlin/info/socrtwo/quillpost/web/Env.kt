package info.socrtwo.quillpost.web

/**
 * Environment settings are named QUILLPOST_*. The app was formerly called Quillbox, so a server
 * configured with the old QUILLBOX_* names keeps working.
 */
fun qpEnv(name: String): String? =
    System.getenv(name) ?: System.getenv(name.replaceFirst("QUILLPOST_", "QUILLBOX_")) // rename:keep

/** System properties quillpost.* with the former quillbox.* as a fallback. */
fun qpProp(name: String): String? =
    System.getProperty(name) ?: System.getProperty(name.replaceFirst("quillpost.", "quillbox.")) // rename:keep
