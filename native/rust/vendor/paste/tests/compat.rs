use paste::paste;

macro_rules! accessor {
    ($name:ident) => {
        paste! {
            #[doc = "Read " $name "."]
            fn [<get_ $name>](&self) -> u32 {
                self.$name
            }
        }
    };
}

struct Sample {
    value: u32,
}

impl Sample {
    accessor!(value);
}

#[test]
fn expands_nested_macros_and_case_modifiers() {
    paste! {
        const [<some_value:upper>]: u32 = 42;
        let [<SomeValue:snake>] = Sample { value: SOME_VALUE };
        assert_eq!(some_value.get_value(), 42);
    }
}

#[test]
fn preserves_legacy_item_and_expr_macros() {
    paste::item! {
        const [<LEGACY _ VALUE>]: u32 = 17;
    }
    assert_eq!(paste::expr! { [<LEGACY _ VALUE>] + 1 }, 18);
}

#[test]
fn expands_lifetimes_and_environment_fragments() {
    paste! {
        fn borrow<[<'a>]>(value: &[<'a>] str, _: &str) -> &[<'a>] str {
            value
        }
        const [<env!("CARGO_PKG_NAME"):upper _ NAME>]: &str = "adapter";
    }
    assert_eq!(borrow("value", "other"), "value");
    assert_eq!(PASTE_NAME, "adapter");
}
