package com.insta.core.di

import com.insta.core.repository.VideoRepositoryImpl
import com.insta.domain.repository.VideoRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface RepoModule {

    @Binds
    fun bindVideoRepository(videoRepositoryImpl: VideoRepositoryImpl): VideoRepository
}