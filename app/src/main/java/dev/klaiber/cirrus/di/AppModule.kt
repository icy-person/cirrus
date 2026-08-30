package dev.klaiber.cirrus.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.klaiber.cirrus.data.repository.SkillRepository
import dev.klaiber.cirrus.domain.tools.DeviceToolSet
import dev.klaiber.cirrus.domain.tools.ListSkillsTool
import dev.klaiber.cirrus.domain.tools.SkillToolSet
import dev.klaiber.cirrus.domain.tools.UseSkillTool
import dev.klaiber.cirrus.domain.tools.SpotifyToolSet
import dev.klaiber.cirrus.domain.tools.device.LocationTool
import dev.klaiber.cirrus.domain.tools.device.MediaControlTool
import dev.klaiber.cirrus.domain.tools.spotify.SpotifyLibraryTool
import dev.klaiber.cirrus.domain.tools.spotify.SpotifyNowPlayingTool
import dev.klaiber.cirrus.domain.tools.spotify.SpotifyPlaybackTool
import dev.klaiber.cirrus.domain.tools.spotify.SpotifyPlaylistEditTool
import dev.klaiber.cirrus.domain.tools.spotify.SpotifySearchTool
import dev.klaiber.cirrus.domain.tools.shell.CalendarTool
import dev.klaiber.cirrus.domain.tools.shell.CleanWorkspaceTool
import dev.klaiber.cirrus.domain.tools.shell.DateTimeTool
import dev.klaiber.cirrus.domain.tools.shell.InstallAppTool
import dev.klaiber.cirrus.domain.tools.shell.ListAppsTool
import dev.klaiber.cirrus.domain.tools.shell.OpenAppTool
import dev.klaiber.cirrus.domain.tools.shell.RunCommandTool
import dev.klaiber.cirrus.domain.tools.shell.SaveFileTool
import dev.klaiber.cirrus.domain.tools.shell.ShellRunner
import dev.klaiber.cirrus.domain.tools.shell.ShellWorkspace
import dev.klaiber.cirrus.domain.tools.shell.SystemInfoTool
import dev.klaiber.cirrus.domain.files.AndroidDownloadSink
import dev.klaiber.cirrus.domain.files.DownloadSink
import dev.klaiber.cirrus.domain.files.ScratchpadBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // The API adds fields over time; unknown ones must not break parsing.
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        isLenient = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The shell's scratch directory.
     *
     * Under `cacheDir` on purpose: Android may reclaim it under storage pressure, which is exactly
     * the right fate for work nobody asked to keep, and it stays out of backups without any further
     * arrangement.
     */
    @Provides
    @Singleton
    fun provideShellWorkspace(@ApplicationContext context: Context): ShellWorkspace =
        ShellWorkspace(File(context.cacheDir, "shell-workspace"))

    @Provides
    @Singleton
    fun provideShellRunner(workspace: ShellWorkspace): ShellRunner = ShellRunner(workspace)

    /**
     * Where a downloaded file goes so the user can open it: the device's Downloads, via MediaStore.
     */
    @Provides
    @Singleton
    fun provideDownloadSink(sink: AndroidDownloadSink): DownloadSink = sink

    /** The scratchpad from the user's side, for the files screen. */
    @Provides
    @Singleton
    fun provideScratchpadBrowser(workspace: ShellWorkspace): ScratchpadBrowser =
        ScratchpadBrowser(workspace)

    /**
     * The skill chooser, assembled by hand because the set carries the repository as well as the
     * two tools — it builds the standing brief from the same list `list_skills` returns, and one
     * source for both is what keeps them from disagreeing.
     */
    @Provides
    @Singleton
    fun provideSkillToolSet(
        repository: SkillRepository,
        list: ListSkillsTool,
        use: UseSkillTool,
    ): SkillToolSet = SkillToolSet(repository = repository, list = list, use = use)

    /** Assembled by hand so that "which of these can act on the phone?" has one obvious answer. */
    @Provides
    @Singleton
    fun provideDeviceToolSet(
        runCommand: RunCommandTool,
        cleanWorkspace: CleanWorkspaceTool,
        saveFile: SaveFileTool,
        dateTime: DateTimeTool,
        calendar: CalendarTool,
        systemInfo: SystemInfoTool,
        listApps: ListAppsTool,
        openApp: OpenAppTool,
        installApp: InstallAppTool,
        mediaControl: MediaControlTool,
        location: LocationTool,
    ): DeviceToolSet = DeviceToolSet(
        shell = listOf(dateTime, calendar, systemInfo, runCommand, cleanWorkspace, saveFile),
        // Media control sits with the apps rather than with Spotify: it drives whatever is playing,
        // which is as likely to be a podcast app, and it is the one path that works without an
        // account of any kind.
        apps = listOf(listApps, openApp, installApp, mediaControl),
        location = listOf(location),
    )

    @Provides
    @Singleton
    fun provideSpotifyToolSet(
        search: SpotifySearchTool,
        nowPlaying: SpotifyNowPlayingTool,
        library: SpotifyLibraryTool,
        playback: SpotifyPlaybackTool,
        playlistEdit: SpotifyPlaylistEditTool,
    ): SpotifyToolSet = SpotifyToolSet(
        all = listOf(search, nowPlaying, library, playback, playlistEdit),
    )

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        context.preferencesDataStoreFile("cirrus_settings")
    }
}
