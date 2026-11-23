# Les DTO kotlinx.serialization sont conservés par les règles fournies par la bibliothèque.
# On garde en plus les noms des DTO Xtream pour des stack traces lisibles en release.
-keepnames class dev.mytv.data.remote.xtream.dto.** { *; }
