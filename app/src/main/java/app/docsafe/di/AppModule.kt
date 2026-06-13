package app.docsafe.di

import android.content.Context
import app.docsafe.security.BiometricKeyManager
import app.docsafe.security.SecureStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSecureStore(@ApplicationContext context: Context): SecureStore = SecureStore(context)

    @Provides
    @Singleton
    fun provideBiometricKeyManager(): BiometricKeyManager = BiometricKeyManager()
}
