package dev.mytv.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.mytv.data.BuildConfig
import dev.mytv.data.local.db.EpgDao
import dev.mytv.data.local.db.LiveDao
import dev.mytv.data.local.db.MyTvDatabase
import dev.mytv.data.local.db.PortalDao
import dev.mytv.data.local.db.VodDao
import dev.mytv.data.local.db.BackupDao
import dev.mytv.data.repository.BackupRepositoryImpl
import dev.mytv.data.repository.SettingsRepositoryImpl
import dev.mytv.domain.repository.BackupRepository
import dev.mytv.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import dev.mytv.data.repository.VodRepositoryImpl
import dev.mytv.domain.repository.VodRepository
import dev.mytv.data.network.EncryptedDns
import dev.mytv.data.network.KillSwitchInterceptor
import dev.mytv.data.network.VpnMonitor
import dev.mytv.domain.repository.NetworkGuard
import dev.mytv.data.repository.LiveRepositoryImpl
import dev.mytv.data.repository.PortalRepositoryImpl
import dev.mytv.domain.repository.LiveRepository
import dev.mytv.domain.repository.PortalRepository
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

@Module
@InstallIn(SingletonComponent::class)
object DataProvidesModule {

    @Provides @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true   // les panels ajoutent des champs à leur guise
        isLenient = true           // tolère du JSON approximatif
        coerceInputValues = true   // null sur un champ non-null -> valeur par défaut
        explicitNulls = false
    }

    /**
     * DNS chiffré. Son client « d'amorçage » ne sert qu'à parler aux résolveurs DoH, dont les IP
     * sont figées : il ne fait donc jamais de requête DNS en clair.
     */
    @Provides @Singleton
    fun provideEncryptedDns(vpn: VpnMonitor): EncryptedDns =
        EncryptedDns(
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                // Même verrou ici : les requêtes DNS elles-mêmes ne partent jamais hors VPN.
                .addInterceptor(KillSwitchInterceptor(vpn::isBlocked))
                .build(),
            isBlocked = vpn::isBlocked,
        )

    @Provides @Singleton
    fun provideOkHttp(@ApplicationContext context: Context, dns: EncryptedDns, vpn: VpnMonitor): OkHttpClient =
        OkHttpClient.Builder()
            // EN PREMIER : sans VPN, la requête est refusée avant DNS, cache ou socket.
            .addInterceptor(KillSwitchInterceptor(vpn::isBlocked))
            .dns(dns)
            // Valeurs pensées pour un portail lent mais vivant : on échoue vite s'il est mort.
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // Cache disque HTTP (logos, futures réponses EPG) : 50 Mo, raisonnable sur 2 Go de RAM / 8 Go de stockage.
            .cache(Cache(File(context.cacheDir, "http"), 50L * 1024 * 1024))
            .addInterceptor { chain ->
                // User-Agent neutre : certains panels rejettent celui d'OkHttp par défaut.
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .apply {
                if (BuildConfig.DEBUG) {
                    // Les identifiants Xtream passent dans l'URL : on les masque dans les logs.
                    val logger = HttpLoggingInterceptor { message ->
                        android.util.Log.d("MyTV-HTTP", message.redactCredentials())
                    }.apply { level = HttpLoggingInterceptor.Level.BASIC }
                    addInterceptor(logger)
                }
            }
            .build()

    /**
     * Client dédié au guide XMLTV : le fichier peut peser plus de 100 Mo, il ne doit pas être
     * coupé par le callTimeout global de 45 s. Partage le pool de connexions du client principal.
     * Pas de cache disque HTTP : inutile de dupliquer 100 Mo sur le stockage du Fire TV.
     */
    @Provides @Singleton @XmltvClient
    fun provideXmltvClient(base: OkHttpClient): OkHttpClient =
        base.newBuilder()
            .cache(null)
            .callTimeout(0, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MyTvDatabase =
        Room.databaseBuilder(context, MyTvDatabase::class.java, "mytv.db").build()

    @Provides
    fun providePortalDao(db: MyTvDatabase): PortalDao = db.portalDao()

    @Provides
    fun provideLiveDao(db: MyTvDatabase): LiveDao = db.liveDao()

    @Provides
    fun provideEpgDao(db: MyTvDatabase): EpgDao = db.epgDao()

    @Provides
    fun provideVodDao(db: MyTvDatabase): VodDao = db.vodDao()

    @Provides
    fun provideBackupDao(db: MyTvDatabase): BackupDao = db.backupDao()

    @Provides @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.appDataStore

    @Provides @Named(PortalRepositoryImpl.IO_DISPATCHER)
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /** Portée de l'application : vit aussi longtemps que le processus (réglages en mémoire). */
    @Provides @Singleton @Named(SettingsRepositoryImpl.APP_SCOPE)
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private const val USER_AGENT = "MyTV/1.0 (Linux; Android TV)"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindsModule {
    @Binds
    abstract fun bindPortalRepository(impl: PortalRepositoryImpl): PortalRepository

    @Binds
    abstract fun bindLiveRepository(impl: LiveRepositoryImpl): LiveRepository

    @Binds
    abstract fun bindNetworkGuard(impl: VpnMonitor): NetworkGuard

    @Binds
    abstract fun bindVodRepository(impl: VodRepositoryImpl): VodRepository

    @Binds
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    abstract fun bindBackupRepository(impl: BackupRepositoryImpl): BackupRepository
}

/** Qualifie le client OkHttp sans timeout global, réservé au téléchargement du guide XMLTV. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class XmltvClient

private val credentialsQueryRegex = Regex("(username|password)=[^&\\s]*")
// Les URLs de flux Xtream portent les identifiants dans le chemin : /live/{user}/{pass}/123.ts
private val credentialsPathRegex = Regex("(/(?:live|movie|series|timeshift)/)[^/\\s]+/[^/\\s]+/")

/** Masque les identifiants (paramètres et chemin) dans une ligne de log. */
internal fun String.redactCredentials(): String =
    credentialsPathRegex.replace(credentialsQueryRegex.replace(this) { "${it.groupValues[1]}=***" }) {
        "${it.groupValues[1]}***/***/"
    }
