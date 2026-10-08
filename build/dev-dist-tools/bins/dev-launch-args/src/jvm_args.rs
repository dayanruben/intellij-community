//! The command `jvm-args`, which writes the JVM arguments of a distribution as a `java` argument file.
//!
//! The rule `intellij_dev_java_launcher` runs `jvm-args` at build time. Then `java` starts with `@<file>`, and no
//! process runs before the JVM. The crate `dev-launch` composes the arguments. The file ends with the program arguments
//! of the row.

use std::ffi::OsString;
use std::io::Write;
use std::path::Path;

use anyhow::{Context as _, bail};
use dev_launch::{Distribution, DistributionFiles, argument_file_text, java_arguments, read_lines};

/// The options of `jvm-args`. Each path names a file of the distribution where the action reads it. `home` and
/// `vm_options_destination` state where the IDE finds the distribution when it starts.
#[derive(Debug)]
struct JvmArgsOptions {
    ide_config: String,
    home: String,
    idea_properties: String,
    vm_options: String,
    vm_options_destination: String,
    product_info: String,
    core_classpath: String,
    flags_file: String,
    /// The program arguments of the row, in their order. The first one names the custom command.
    program_args: Vec<String>,
    runtime_module_repository: bool,
    output: String,
}

/// `jvm-args`: writes the argument file of a distribution. It returns the exit code: 2 for an option error, 1 for any
/// other error.
pub(crate) fn run_jvm_args(args: &[OsString], errors: &mut dyn Write) -> u8 {
    let options = match parse_jvm_args(args) {
        Ok(options) => options,
        Err(error) => {
            cli::report(errors, &error);
            return 2;
        }
    };
    match write_jvm_args(&options) {
        Ok(()) => 0,
        Err(error) => {
            cli::report(errors, &error);
            1
        }
    }
}

fn parse_jvm_args(args: &[OsString]) -> anyhow::Result<JvmArgsOptions> {
    let mut options = cli::parse(args.iter().cloned())?;
    let result = JvmArgsOptions {
        ide_config: options.require("--ide-config")?,
        home: options.require("--home")?,
        idea_properties: options.require("--idea-properties")?,
        vm_options: options.require("--vm-options")?,
        vm_options_destination: options.require("--vm-options-destination")?,
        product_info: options.require("--product-info")?,
        core_classpath: options.require("--core-classpath")?,
        flags_file: options.require("--flags-file")?,
        program_args: options.take_all("--program-arg")?,
        runtime_module_repository: options.flag("--runtime-module-repository")?,
        output: options.require("--output")?,
    };
    options.finish()?;
    if !Path::new(&result.home).is_absolute() {
        bail!("--home must be an absolute path: {}", result.home);
    }
    Ok(result)
}

fn write_jvm_args(options: &JvmArgsOptions) -> anyhow::Result<()> {
    let files = DistributionFiles {
        ide_config: Path::new(&options.ide_config),
        idea_properties: Path::new(&options.idea_properties),
        vm_options: Path::new(&options.vm_options),
        product_info: Path::new(&options.product_info),
        core_classpath: Path::new(&options.core_classpath),
    };
    let distribution = Distribution::read(
        &options.home,
        &files,
        &options.vm_options_destination,
        options.runtime_module_repository,
    )?;
    let command_line = read_lines(Path::new(&options.flags_file))?;
    let mut arguments = java_arguments(command_line, distribution, options.program_args.first().map(String::as_str))?;
    arguments.extend(options.program_args.iter().cloned());
    let text = argument_file_text(&arguments)?;
    std::fs::write(&options.output, text).with_context(|| format!("write {}", options.output))
}

#[cfg(test)]
mod tests;
