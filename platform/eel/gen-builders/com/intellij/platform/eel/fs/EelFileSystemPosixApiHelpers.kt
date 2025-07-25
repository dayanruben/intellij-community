// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
/**
 * This file is generated by [com.intellij.platform.eel.codegen.BuildersGeneratorTest].
 */
package com.intellij.platform.eel.fs

import com.intellij.platform.eel.EelResult
import com.intellij.platform.eel.GeneratedBuilder
import com.intellij.platform.eel.OwnedBuilder
import com.intellij.platform.eel.fs.EelFileSystemApi.StatError
import com.intellij.platform.eel.fs.EelFileSystemApi.SymlinkPolicy
import com.intellij.platform.eel.path.EelPath
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.CheckReturnValue


@GeneratedBuilder.Result
@ApiStatus.Internal
fun EelFileSystemPosixApi.listDirectoryWithAttrs(
  path: EelPath,
): EelFileSystemPosixApiHelpers.ListDirectoryWithAttrs =
  EelFileSystemPosixApiHelpers.ListDirectoryWithAttrs(
    owner = this,
    path = path,
  )

@GeneratedBuilder.Result
@ApiStatus.Internal
fun EelFileSystemPosixApi.stat(
  path: EelPath,
): EelFileSystemPosixApiHelpers.Stat =
  EelFileSystemPosixApiHelpers.Stat(
    owner = this,
    path = path,
  )

@ApiStatus.Internal
object EelFileSystemPosixApiHelpers {
  /**
   * Create it via [com.intellij.platform.eel.fs.EelFileSystemPosixApi.listDirectoryWithAttrs].
   */
  @GeneratedBuilder.Result
  @ApiStatus.Internal
  class ListDirectoryWithAttrs(
    private val owner: EelFileSystemPosixApi,
    private var path: EelPath,
  ) : OwnedBuilder<EelResult<Collection<Pair<String, EelPosixFileInfo>>, EelFileSystemApi.ListDirectoryError>> {
    private var symlinkPolicy: SymlinkPolicy = SymlinkPolicy.DO_NOT_RESOLVE

    fun path(arg: EelPath): ListDirectoryWithAttrs = apply {
      this.path = arg
    }

    fun symlinkPolicy(arg: SymlinkPolicy): ListDirectoryWithAttrs = apply {
      this.symlinkPolicy = arg
    }

    fun doNotResolve(): ListDirectoryWithAttrs =
      symlinkPolicy(SymlinkPolicy.DO_NOT_RESOLVE)

    fun justResolve(): ListDirectoryWithAttrs =
      symlinkPolicy(SymlinkPolicy.JUST_RESOLVE)

    fun resolveAndFollow(): ListDirectoryWithAttrs =
      symlinkPolicy(SymlinkPolicy.RESOLVE_AND_FOLLOW)

    /**
     * Complete the builder and call [com.intellij.platform.eel.fs.EelFileSystemPosixApi.listDirectoryWithAttrs]
     * with an instance of [com.intellij.platform.eel.fs.EelFileSystemApi.ListDirectoryWithAttrsArgs].
     */
    @CheckReturnValue
    override suspend fun eelIt(): EelResult<Collection<Pair<String, EelPosixFileInfo>>, EelFileSystemApi.ListDirectoryError> =
      owner.listDirectoryWithAttrs(
        ListDirectoryWithAttrsArgsImpl(
          path = path,
          symlinkPolicy = symlinkPolicy,
        )
      )
  }

  /**
   * Create it via [com.intellij.platform.eel.fs.EelFileSystemPosixApi.stat].
   */
  @GeneratedBuilder.Result
  @ApiStatus.Internal
  class Stat(
    private val owner: EelFileSystemPosixApi,
    private var path: EelPath,
  ) : OwnedBuilder<EelResult<EelPosixFileInfo, StatError>> {
    private var symlinkPolicy: SymlinkPolicy = SymlinkPolicy.DO_NOT_RESOLVE

    fun path(arg: EelPath): Stat = apply {
      this.path = arg
    }

    fun symlinkPolicy(arg: SymlinkPolicy): Stat = apply {
      this.symlinkPolicy = arg
    }

    fun doNotResolve(): Stat =
      symlinkPolicy(SymlinkPolicy.DO_NOT_RESOLVE)

    fun justResolve(): Stat =
      symlinkPolicy(SymlinkPolicy.JUST_RESOLVE)

    fun resolveAndFollow(): Stat =
      symlinkPolicy(SymlinkPolicy.RESOLVE_AND_FOLLOW)

    /**
     * Complete the builder and call [com.intellij.platform.eel.fs.EelFileSystemPosixApi.stat]
     * with an instance of [com.intellij.platform.eel.fs.EelFileSystemApi.StatArgs].
     */
    @CheckReturnValue
    override suspend fun eelIt(): EelResult<EelPosixFileInfo, StatError> =
      owner.stat(
        StatArgsImpl(
          path = path,
          symlinkPolicy = symlinkPolicy,
        )
      )
  }
}