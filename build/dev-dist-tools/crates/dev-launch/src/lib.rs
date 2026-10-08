// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

//! `dev-launch` composes the `java` command line of a dev distribution and the text of its argument file.
//!
//! The argument file writer `dev-launch-args` runs it in a Bazel action. A launcher that starts the IDE itself can run
//! it on the OS that the IDE runs on. The steps are:
//!
//! 1. [`Distribution::read`] reads the main class, the properties and the class path of the distribution.
//! 2. [`java_arguments`] puts the caller flags before the properties of the distribution, the class path and the main
//!    class.
//! 3. [`argument_file_text`] gives the text of the argument file, one quoted argument per line.

mod jvm_args;
mod properties;

pub use jvm_args::{
    Distribution, DistributionFiles, PATH_LIST_SEPARATOR, argument_file_text, java_arguments, quote_argument, read_class_path,
};
pub use properties::{ProductInfo, RUNTIME_MODULE_REPOSITORY_PROPERTY, custom_command, properties_of_files, read_lines};
