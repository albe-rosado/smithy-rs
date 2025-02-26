/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

use crate::endpoint_lib::diagnostic::DiagnosticCollector;

pub(crate) fn is_valid_host_label(
    label: &str,
    allow_dots: bool,
    e: &mut DiagnosticCollector,
) -> bool {
    if allow_dots {
        for part in label.split('.') {
            if !is_valid_host_label(part, false, e) {
                return false;
            }
        }
        true
    } else {
        if label.is_empty() || label.len() > 63 {
            e.report_error("host was too short or too long");
            return false;
        }
        label.chars().enumerate().all(|(idx, ch)| match (ch, idx) {
            ('-', 0) => {
                e.report_error("cannot start with `-`");
                false
            }
            _ => ch.is_alphanumeric() || ch == '-',
        })
    }
}

#[cfg(test)]
mod test {
    use proptest::proptest;

    fn is_valid_host_label(label: &str, allow_dots: bool) -> bool {
        super::is_valid_host_label(label, allow_dots, &mut DiagnosticCollector::new())
    }

    #[test]
    fn basic_cases() {
        assert_eq!(is_valid_host_label("", false), false);
        assert_eq!(is_valid_host_label("", true), false);
        assert_eq!(is_valid_host_label(".", true), false);
        assert_eq!(is_valid_host_label("a.b", true), true);
        assert_eq!(is_valid_host_label("a.b", false), false);
        assert_eq!(is_valid_host_label("a.b.", true), false);
        assert_eq!(is_valid_host_label("a.b.c", true), true);
        assert_eq!(is_valid_host_label("a_b", true), false);
        assert_eq!(is_valid_host_label(&"a".repeat(64), false), false);
        assert_eq!(
            is_valid_host_label(&format!("{}.{}", "a".repeat(63), "a".repeat(63)), true),
            true
        );
    }

    #[test]
    fn start_bounds() {
        assert_eq!(is_valid_host_label("-foo", false), false);
        assert_eq!(is_valid_host_label("-foo", true), false);
        assert_eq!(is_valid_host_label(".foo", true), false);
        assert_eq!(is_valid_host_label("a-b.foo", true), true);
    }

    use crate::endpoint_lib::diagnostic::DiagnosticCollector;
    use proptest::prelude::*;
    proptest! {
        #[test]
        fn no_panics(s in any::<String>(), dots in any::<bool>()) {
            is_valid_host_label(&s, dots);
        }
    }
}
