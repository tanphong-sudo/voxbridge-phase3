package android.content

import android.content.res.AssetManager
import java.io.File

/**
 * JVM stand-ins for the two android classes [ModelInstaller] touches, so its extraction logic is
 * testable in the mock harness. Backed by a real directory tree: `AssetManager(root)` mirrors the
 * real semantics that matter here — `list(path)` returns the folder's entries (empty for a file
 * or absent path), `open(path)` streams a file and throws for a directory/absent path.
 */
open class Context(val assets: AssetManager)
