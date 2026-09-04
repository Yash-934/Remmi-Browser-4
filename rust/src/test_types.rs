use adblock::engine::BlockerResult;
fn main() {
    let _r: BlockerResult = unimplemented!();
    let _ = _r.matched;
    let _ = _r.exception;
    let _ = _r.important;
    let _ = _r.redirect;
    let _ = _r.rewritten_url;
}
