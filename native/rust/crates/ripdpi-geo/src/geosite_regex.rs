use std::borrow::Cow;

use regex::Regex;

pub(super) fn compile(pattern: &str) -> Result<Regex, regex::Error> {
    Regex::new(&unquote(pattern))
}

fn unquote(pattern: &str) -> Cow<'_, str> {
    if !pattern.contains(r"\Q") {
        return Cow::Borrowed(pattern);
    }
    let mut output = String::with_capacity(pattern.len());
    let mut chars = pattern.chars();
    let mut in_class = false;
    let mut class_initial = false;
    let mut class_negation = false;
    while let Some(ch) = chars.next() {
        if ch != '\\' {
            if in_class {
                if ch == '[' && chars.as_str().starts_with(':') {
                    let rest = chars.as_str();
                    if let Some(end) = rest.find(":]") {
                        let end = end + 2;
                        output.push(ch);
                        output.push_str(&rest[..end]);
                        chars = rest[end..].chars();
                        class_initial = false;
                        class_negation = false;
                        continue;
                    }
                }
                if ch == ']' && !class_initial {
                    in_class = false;
                }
                if ch != '^' || !class_negation {
                    class_initial = false;
                }
                class_negation = false;
                if ch == '[' {
                    output.push('\\');
                }
            } else if ch == '[' {
                in_class = true;
                class_initial = true;
                class_negation = true;
            }
            output.push(ch);
            continue;
        }
        match chars.next() {
            // Go quotes through the end of the expression when the closing escape is absent.
            Some('Q') if !in_class => {
                let remainder = chars.as_str();
                let (literal, rest) = remainder.split_once(r"\E").unwrap_or((remainder, ""));
                output.push_str(&regex::escape(literal));
                chars = rest.chars();
            }
            Some(escaped) => {
                output.push('\\');
                output.push(escaped);
            }
            None => output.push('\\'),
        }
        if in_class {
            class_initial = false;
            class_negation = false;
        }
    }
    Cow::Owned(output)
}

#[cfg(test)]
mod tests {
    use super::compile;

    #[test]
    fn quoting_preserves_escaped_openers_and_unterminated_literals() {
        assert!(compile(r"^\\Qexample\.com$").expect("escaped opener").is_match(r"\Qexample.com"));
        assert!(compile(r"^\Qexample.com").expect("unclosed quotation").is_match("example.com"));
        assert!(!compile(r"^\Qexample.com").expect("unclosed quotation").is_match("exampleXcom"));
        assert!(compile(r"^\Qé.[x]\E$").expect("quoted Unicode").is_match("é.[x]"));
        assert!(compile(r"^\Qfoo\\E$").expect("literal backslash before end quote").is_match("foo\\"));
        assert!(compile(r"\E").is_err(), "an end quote outside quotation is invalid");
        for pattern in [r"[\Qx\E]", r"[]\Qx\E]", r"[^]\Qx\E]", r"[[:alpha:]\Qx\E]"] {
            assert!(compile(pattern).is_err(), "Go quotation is not valid in a character class: {pattern}");
        }
        assert!(compile(r"[[]\Qx\E").expect("literal class opener").is_match("[x"));
        assert!(compile(r"[[:alpha:]]\Q.x\E").expect("POSIX class then quotation").is_match("a.x"));
    }
}
