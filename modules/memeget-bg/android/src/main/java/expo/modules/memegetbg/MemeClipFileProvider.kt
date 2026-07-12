package expo.modules.memegetbg

import androidx.core.content.FileProvider

// Dedicated FileProvider subclass for clipboard file grants. Subclassed (rather
// than declaring androidx.core.content.FileProvider directly) so this module's
// FILE_PROVIDER_PATHS meta-data can't collide in the manifest merge with other
// libraries that also ship a FileProvider (expo-file-system, expo-clipboard…).
class MemeClipFileProvider : FileProvider()
