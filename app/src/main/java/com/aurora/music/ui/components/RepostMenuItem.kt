package com.aurora.music.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.aurora.music.R
import com.aurora.music.data.MusicRepository
import com.aurora.music.data.remote.SoundCloudCheckException
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// the change runs in the app's scope because picking it closes the menu that started it
class RepostMenu(private val repository: MusicRepository, private val scope: CoroutineScope, private val notify: (String) -> Unit) {
    fun supports(song: Song) = repository.supportsReposts(song.id)
    suspend fun isReposted(song: Song) = try { repository.isReposted(song.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    fun set(song: Song, reposted: Boolean) {
        scope.launch {
            val result = runCatching { repository.setReposted(song.id, reposted) }
            notify(appString(when {
                result.exceptionOrNull() is SoundCloudCheckException -> R.string.comment_needs_verify
                result.getOrNull() != true -> R.string.repost_failed
                reposted -> R.string.repost_done
                else -> R.string.repost_removed
            }))
        }
    }
}

val LocalRepostMenu = staticCompositionLocalOf<RepostMenu?> { null }

@Composable
fun RepostMenuItem(song: Song, close: () -> Unit) {
    val reposts = LocalRepostMenu.current?.takeIf { it.supports(song) } ?: return
    var reposted by remember(song.id) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(song.id) { reposted = reposts.isReposted(song) }
    DropdownMenuItem(
        text = { Text(appString(if (reposted == true) R.string.repost_remove else R.string.repost)) },
        enabled = reposted != null,
        onClick = { val next = reposted != true; close(); reposts.set(song, next) },
        leadingIcon = { Icon(Icons.Filled.Repeat, null, tint = if (reposted == true) MaterialTheme.colorScheme.primary else LocalContentColor.current) },
    )
}
