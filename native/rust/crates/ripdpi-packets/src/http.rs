mod detect;
mod mutation;
mod parse;
mod redirect;
#[cfg(test)]
mod tests;

pub use detect::*;
pub use mutation::*;
pub use redirect::*;

#[cfg(test)]
use parse::get_http_code;
