package com.prabhat.djmix.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.prabhat.djmix.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Teaches Hilt how to build what it cannot construct itself: Firebase's own singletons have no @Inject
 * constructor, so we hand them over here. Our own classes (AuthRepository, CloudRepository, Prep) need no entry:
 * their `@Inject constructor` is enough.
 *
 * A build without google-services.json has no Firebase at all (BuildConfig.CLOUD is false): these are then null, and
 * the repositories behave as permanently signed out. Kotlin's `?` is enough for Hilt to accept a nullable binding.
 */
@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {
    @Provides @Singleton fun auth(): FirebaseAuth? = if (BuildConfig.CLOUD) FirebaseAuth.getInstance() else null
    @Provides @Singleton fun storage(): FirebaseStorage? = if (BuildConfig.CLOUD) FirebaseStorage.getInstance() else null
}
