package dev.naz.jogtherobot.module

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.naz.jogtherobot.data.datasource.ONNXModelDataSource
import dev.naz.jogtherobot.data.repository.ONNXPoseDetectionRepository
import dev.naz.jogtherobot.domain.usecase.GestureDetector


@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    fun provideONNXModelDataSource(
        @ApplicationContext context: Context
    ): ONNXModelDataSource{
        return ONNXModelDataSource(context)
    }

    @Provides
    fun provideONNXPoseDetectionRepository(
        dataSource: ONNXModelDataSource
    ): ONNXPoseDetectionRepository{
        return ONNXPoseDetectionRepository(dataSource)
    }


    @Provides
    fun provideGestureDetector(): GestureDetector {
        return GestureDetector()
    }
}