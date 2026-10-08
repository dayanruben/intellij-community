//! The lane IDE: the preparation of one context, and the collection of the IDE runs under an IDE root.
//!
//! The UI daemon decides what to launch and when. This module owns the files of a context, and the run supervisor
//! owns the process: the caller starts the IDE with `start --root <context> --run run-ide-<launchName>`, so the
//! context is the run slot too, and a preparation never lands beside a live IDE.
//!
//! The documents are declared in `avl_wire::ide`. Every refusal is `guest_<verb>_failed` with exit 70, except the
//! two that a caller acts on: `ide_running` and `ide_display_missing`.

pub(crate) mod gc;
pub(crate) mod prepare;
pub(crate) mod reap;

use std::path::Path;

use avl_wire::supervisor::{Phase, RunState};

use crate::cli::RootArgs;
use crate::reply::AgentRefusal;
use crate::supervisor::{self, System};

/// The live run of the run slot `context`, or `None` when the slot is free or the context does not exist.
///
/// A run that the reconcile finishes now is not live. An unreadable slot is a refusal: a caller that read it as free
/// would land a launch beside a live IDE.
pub(crate) fn live_run(system: &dyn System, context: &Path) -> Result<Option<RunState>, AgentRefusal> {
    if !context.join("active.json").exists() {
        return Ok(None);
    }
    let reply = supervisor::active(
        system,
        &RootArgs {
            root: context.to_path_buf(),
        },
    )?;
    Ok(reply.active.filter(|state| state.phase != Phase::Finished))
}
