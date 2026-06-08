use cvlr::mathint::NativeInt;

pub trait NativeSignedMath: Sized + Copy {
    type Unsigned;

    fn n_unsigned_abs(self) -> Self::Unsigned;
    fn n_checked_abs(self) -> Option<Self>;
    fn n_is_positive(self) -> bool;
    fn n_is_negative(self) -> bool;
    fn n_checked_mul(self, rhs: Self) -> Option<Self>;
    fn n_checked_div(self, rhs: Self) -> Option<Self>;
    fn n_checked_add(self, rhs: Self) -> Option<Self>;
    fn n_checked_sub(self, rhs: Self) -> Option<Self>;
    fn n_saturating_mul(self, rhs: Self) -> Self;
    fn n_saturating_add(self, rhs: Self) -> Self;
    fn n_saturating_sub(self, rhs: Self) -> Self;
    fn n_saturating_neg(self) -> Self;
    fn n_is_gt(self, other: Self) -> bool;
    fn n_is_lt(self, other: Self) -> bool;
    fn n_neg_to_unsigned(self) -> Self::Unsigned;
    fn n_saturating_abs(self) -> Self;
    fn n_clamp_pos(self) -> Self::Unsigned;
    fn n_saturating_div(self, rhs: Self) -> Self;
    fn n_sub(self, rhs: Self) -> Self;
}

pub trait NativeUnsignedMath: Sized + Copy {
    type Signed;

    fn n_checked_mul(self, rhs: Self) -> Option<Self>;
    fn n_checked_add(self, rhs: Self) -> Option<Self>;
    fn n_checked_sub(self, rhs: Self) -> Option<Self>;
    fn n_saturating_add(self, rhs: Self) -> Self;
    fn n_saturating_sub(self, rhs: Self) -> Self;
    fn n_saturating_mul(self, rhs: Self) -> Self;
    fn n_checked_div(self, rhs: Self) -> Option<Self>;
    fn n_saturating_div(self, rhs: Self) -> Self;
    fn n_to_signed_clamped(self) -> Self::Signed;
}

macro_rules! impl_native_signed_math {
    (
        $mod_name:ident,
        $signed:ty,
        $unsigned:ty,
        $bits:literal,
        $u_max:expr
    ) => {
        pub mod $mod_name {
            use super::NativeInt;
            use cvlr::prelude::*;
            use core::ops::Neg;

            /// Returns `|x|` as unsigned using two's-complement arithmetic.
            pub fn unsigned_abs(x: $signed) -> $unsigned {
                let r = NativeInt::from(x as $unsigned).sext($bits);
                if r.sge(NativeInt::from(0)) {
                    x as $unsigned
                } else {
                    Into::<$unsigned>::into(r.neg().mask($bits))
                }
            }

            pub fn checked_abs(x: $signed) -> Option<$signed> {
                if x == <$signed>::MIN {
                    return None
                }
                let r = NativeInt::from(x as $unsigned).sext($bits);
                if r.sge(NativeInt::from(0)) {
                    Some(x)
                } else {
                    Some(Into::<$unsigned>::into(r.neg()) as $signed)
                }
            }

            /// Returns true when `x > 0`.
            pub fn is_positive(x: $signed) -> bool {
                let r = NativeInt::from(x as $unsigned).sext($bits);
                r.sgt(NativeInt::from(0))
            }

            /// Returns true when `x < 0`.
            pub fn is_negative(x: $signed) -> bool {
                let r = NativeInt::from(x as $unsigned).sext($bits);
                r.slt(NativeInt::from(0))
            }

            /// Multiplies two signed values and returns `None` on overflow.
            pub fn checked_mul(lhs: $signed, rhs: $signed) -> Option<$signed> {
                let lhs = NativeInt::from(lhs as $unsigned).sext($bits);
                let rhs = NativeInt::from(rhs as $unsigned).sext($bits);

                let res = lhs * rhs;
                let smax = $u_max / 2; // 2^(bits-1) − 1 (max positive signed)
                let smin: NativeInt = smax + 1; // 2^(bits-1) (magnitude of signed MIN)
                let smin = smin.sext($bits);

                if res.sge(smin) && res.sle(smax) {
                    Some(Into::<$unsigned>::into(res.mask($bits)) as $signed)
                } else {
                    None
                }
            }

            /// Divides two signed values and returns `None` when division is invalid.
            pub fn checked_div(lhs: $signed, rhs: $signed) -> Option<$signed> {
                if (rhs == 0) || (lhs == <$signed>::MIN && rhs == -1) {
                    return None;
                }
                match (lhs < 0, rhs < 0) {
                    (false, false) => {
                        let lhs = NativeInt::from(lhs as $unsigned);
                        let rhs = NativeInt::from(rhs as $unsigned);
                        let q = lhs / rhs;
                        Some(Into::<$unsigned>::into(q.mask($bits)) as $signed)
                    }
                    (true, true) => {
                        let lhs = NativeInt::from(lhs as $unsigned).sext($bits).neg();
                        let rhs = NativeInt::from(rhs as $unsigned).sext($bits).neg();
                        let q = lhs / rhs;
                        Some(Into::<$unsigned>::into(q.mask($bits)) as $signed)
                    }
                    (false, true) => {
                        let lhs = NativeInt::from(lhs as $unsigned);
                        let rhs = NativeInt::from(rhs as $unsigned).sext($bits).neg();
                        let q = lhs / rhs;
                        Some(Into::<$unsigned>::into(q.neg().mask($bits)) as $signed)
                    }
                    (true, false) => {
                        let lhs = NativeInt::from(lhs as $unsigned).sext($bits).neg();
                        let rhs = NativeInt::from(rhs as $unsigned);
                        let q = lhs / rhs;
                        Some(Into::<$unsigned>::into(q.neg().mask($bits)) as $signed)
                    }
                }
            }

            /// Adds two signed values and returns `None` on overflow.
            pub fn checked_add(lhs: $signed, rhs: $signed) -> Option<$signed> {
                let lhs = NativeInt::from(lhs as $unsigned).sext($bits);
                let rhs = NativeInt::from(rhs as $unsigned).sext($bits);

                let res = lhs + rhs;
                let smax = $u_max / 2;
                let smin: NativeInt = smax + 1;
                let smin = smin.sext($bits);

                if res.sge(smin) && res.sle(smax) {
                    Some(Into::<$unsigned>::into(res.mask($bits)) as $signed)
                } else {
                    None
                }
            }

            /// Subtracts two signed values and returns `None` on overflow.
            pub fn checked_sub(lhs: $signed, rhs: $signed) -> Option<$signed> {
                let lhs = NativeInt::from(lhs as $unsigned).sext($bits);
                let rhs = NativeInt::from(rhs as $unsigned).sext($bits);

                let res = lhs - rhs;
                let smax = $u_max / 2;
                let smin: NativeInt = smax + 1;
                let smin = smin.sext($bits);

                if res.sge(smin) && res.sle(smax) {
                    Some(Into::<$unsigned>::into(res.mask($bits)) as $signed)
                } else {
                    None
                }
            }

            /// Multiplies two signed values, saturating to `MIN..=MAX`.
            pub fn saturating_mul(lhs: $signed, rhs: $signed) -> $signed {
                let lhs = NativeInt::from(lhs as $unsigned).sext($bits);
                let rhs = NativeInt::from(rhs as $unsigned).sext($bits);

                let res = lhs * rhs;
                let smax = $u_max / 2;
                let smin: NativeInt = smax + 1;
                let smin_sext = smin.sext($bits);

                if res.slt(smin_sext) {
                    Into::<$unsigned>::into(smin) as $signed
                } else if res.sgt(smax) {
                    Into::<$unsigned>::into(smax) as $signed
                } else {
                    Into::<$unsigned>::into(res.mask($bits)) as $signed
                }
            }

            /// Adds two signed values, saturating to `MIN..=MAX`.
            pub fn saturating_add(lhs: $signed, rhs: $signed) -> $signed {
                let lhs = NativeInt::from(lhs as $unsigned).sext($bits);
                let rhs = NativeInt::from(rhs as $unsigned).sext($bits);

                let res = lhs + rhs;
                let smax = $u_max / 2;
                let smin: NativeInt = smax + 1;
                let smin_sext = smin.sext($bits);

                if res.slt(smin_sext) {
                    Into::<$unsigned>::into(smin) as $signed
                } else if res.sgt(smax) {
                    Into::<$unsigned>::into(smax) as $signed
                } else {
                    Into::<$unsigned>::into(res.mask($bits)) as $signed
                }
            }

            /// Subtracts two signed values, saturating to `MIN..=MAX`.
            pub fn saturating_sub(lhs: $signed, rhs: $signed) -> $signed {
                let lhs = NativeInt::from(lhs as $unsigned).sext($bits);
                let rhs = NativeInt::from(rhs as $unsigned).sext($bits);

                let res = lhs - rhs;
                let smax = $u_max / 2;
                let smin: NativeInt = smax + 1;
                let smin_sext = smin.sext($bits);

                if res.slt(smin_sext) {
                    Into::<$unsigned>::into(smin) as $signed
                } else if res.sgt(smax) {
                    Into::<$unsigned>::into(smax) as $signed
                } else {
                    Into::<$unsigned>::into(res.mask($bits)) as $signed
                }
            }

            /// Negates `x`, saturating signed `MIN` to signed `MAX`.
            pub fn saturating_neg(x: $signed) -> $signed {
                let x = NativeInt::from(x as $unsigned).sext($bits);
                let smax = $u_max / 2;

                let r: NativeInt = -x;

                if r.sgt(smax) {
                    Into::<$unsigned>::into(smax) as $signed
                } else {
                    Into::<$unsigned>::into(r.mask($bits)) as $signed
                }
            }

            /// Returns true when `x > y` under signed comparison.
            pub fn is_gt(x: $signed, y: $signed) -> bool {
                let x = NativeInt::from(x as $unsigned).sext($bits);
                let y = NativeInt::from(y as $unsigned).sext($bits);

                x.sgt(y)
            }

            /// Returns true when `x < y` under signed comparison.
            pub fn is_lt(x: $signed, y: $signed) -> bool {
                let x = NativeInt::from(x as $unsigned).sext($bits);
                let y = NativeInt::from(y as $unsigned).sext($bits);

                x.slt(y)
            }

            /// Returns `-val` reinterpreted as unsigned two's-complement bits.
            pub fn neg_to_unsigned(val: $signed) -> $unsigned {
                let x = NativeInt::from(val as $unsigned).sext($bits);
                let r: NativeInt = -x;
                Into::<$unsigned>::into(r.mask($bits))
            }

            /// Returns `|val|` as signed, saturating signed `MIN` to signed `MAX`.
            pub fn saturating_abs(val: $signed) -> $signed {
                let x = NativeInt::from(val as $unsigned).sext($bits);

                let smax = $u_max / 2;
                let zero = NativeInt::from(0);

                if x.sge(zero) {
                    val
                } else {
                    let neg_x = -x;
                    if neg_x > smax {
                        Into::<$unsigned>::into(smax) as $signed
                    } else {
                        Into::<$unsigned>::into(neg_x) as $signed
                    }
                }
            }

            /// Clamps signed input to positive range and returns unsigned.
            pub fn clamp_pos(val: $signed) -> $unsigned {
                let x = NativeInt::from(val as $unsigned).sext($bits);
                let zero = NativeInt::from(0);
                if x.sgt(zero) {
                    val as $unsigned
                } else {
                    0
                }
            }

            /// Divides `a / b` and saturates invalid results to signed `MAX`.
            pub fn saturating_div(a: $signed, b: $signed) -> $signed {
                cvlr_assume!(b != 0);
                let smax = Into::<$unsigned>::into($u_max / 2) as $signed;
                checked_div(a, b).unwrap_or(smax)
            }

            /// Subtracts with wrapping semantics (masked to the bit width).
            pub fn sub(a: $signed, b: $signed) -> $signed {
                let a = NativeInt::from(a as $unsigned).sext($bits);
                let b = NativeInt::from(b as $unsigned).sext($bits);
                let res = a - b;
                Into::<$unsigned>::into(res.mask($bits)) as $signed
            }
        }

        impl NativeSignedMath for $signed {
            type Unsigned = $unsigned;

            #[inline]
            fn n_unsigned_abs(self) -> Self::Unsigned { $mod_name::unsigned_abs(self) }
            #[inline]
            fn n_checked_abs(self) -> Option<Self> { $mod_name::checked_abs(self) }
            #[inline]
            fn n_is_positive(self) -> bool { $mod_name::is_positive(self) }
            #[inline]
            fn n_is_negative(self) -> bool { $mod_name::is_negative(self) }
            #[inline]
            fn n_checked_mul(self, rhs: Self) -> Option<Self> { $mod_name::checked_mul(self, rhs) }
            #[inline]
            fn n_checked_div(self, rhs: Self) -> Option<Self> { $mod_name::checked_div(self, rhs) }
            #[inline]
            fn n_checked_add(self, rhs: Self) -> Option<Self> { $mod_name::checked_add(self, rhs) }
            #[inline]
            fn n_checked_sub(self, rhs: Self) -> Option<Self> { $mod_name::checked_sub(self, rhs) }
            #[inline]
            fn n_saturating_mul(self, rhs: Self) -> Self { $mod_name::saturating_mul(self, rhs) }
            #[inline]
            fn n_saturating_add(self, rhs: Self) -> Self { $mod_name::saturating_add(self, rhs) }
            #[inline]
            fn n_saturating_sub(self, rhs: Self) -> Self { $mod_name::saturating_sub(self, rhs) }
            #[inline]
            fn n_saturating_neg(self) -> Self { $mod_name::saturating_neg(self) }
            #[inline]
            fn n_is_gt(self, other: Self) -> bool { $mod_name::is_gt(self, other) }
            #[inline]
            fn n_is_lt(self, other: Self) -> bool { $mod_name::is_lt(self, other) }
            #[inline]
            fn n_neg_to_unsigned(self) -> Self::Unsigned { $mod_name::neg_to_unsigned(self) }
            #[inline]
            fn n_saturating_abs(self) -> Self { $mod_name::saturating_abs(self) }
            #[inline]
            fn n_clamp_pos(self) -> Self::Unsigned { $mod_name::clamp_pos(self) }
            #[inline]
            fn n_saturating_div(self, rhs: Self) -> Self { $mod_name::saturating_div(self, rhs) }
            #[inline]
            fn n_sub(self, rhs: Self) -> Self { $mod_name::sub(self, rhs) }
        }
    };
}

macro_rules! impl_native_unsigned_math {
    (
        $mod_name:ident,
        $unsigned:ty,
        $signed:ty,
        $u_max:expr
    ) => {
        pub mod $mod_name {
            use super::NativeInt;
            use cvlr::prelude::*;

            pub fn checked_mul(a: $unsigned, b: $unsigned) -> Option<$unsigned> {
                let res = NativeInt::from(a) * NativeInt::from(b);
                if res > $u_max {
                    None
                } else {
                    Some(res.into())
                }
            }

            pub fn checked_add(a: $unsigned, b: $unsigned) -> Option<$unsigned> {
                let res = NativeInt::from(a) + NativeInt::from(b);
                if res > $u_max {
                    None
                } else {
                    Some(res.into())
                }
            }

            pub fn checked_sub(a: $unsigned, b: $unsigned) -> Option<$unsigned> {
                let a = NativeInt::from(a);
                let b = NativeInt::from(b);
                if a >= b {
                    Some((a - b).into())
                } else {
                    None
                }
            }

            pub fn saturating_add(a: $unsigned, b: $unsigned) -> $unsigned {
                let res = NativeInt::from(a) + NativeInt::from(b);
                let u_max = $u_max;
                if res > u_max {
                    u_max.into()
                } else {
                    res.into()
                }
            }

            pub fn saturating_sub(a: $unsigned, b: $unsigned) -> $unsigned {
                let a = NativeInt::from(a);
                let b = NativeInt::from(b);
                if a < b {
                    0
                } else {
                    (a - b).into()
                }
            }

            pub fn saturating_mul(a: $unsigned, b: $unsigned) -> $unsigned {
                let res = NativeInt::from(a) * NativeInt::from(b);
                if res > $u_max {
                    Into::<$unsigned>::into($u_max)
                } else {
                    res.into()
                }
            }

            pub fn checked_div(a: $unsigned, b: $unsigned) -> Option<$unsigned> {
                if b == 0 {
                    None
                } else {
                    Some((NativeInt::from(a) / NativeInt::from(b)).into())
                }
            }

            pub fn saturating_div(a: $unsigned, b: $unsigned) -> $unsigned {
                cvlr_assume!(b != 0);
                checked_div(a, b).unwrap()
            }

            pub fn to_signed_clamped(x: $unsigned) -> $signed {
                let signed_max = $u_max / 2;
                if NativeInt::from(x) > signed_max {
                    Into::<$unsigned>::into(signed_max) as $signed
                } else {
                    x as $signed
                }
            }
        }

        impl NativeUnsignedMath for $unsigned {
            type Signed = $signed;

            #[inline]
            fn n_checked_mul(self, rhs: Self) -> Option<Self> { $mod_name::checked_mul(self, rhs) }
            #[inline]
            fn n_checked_add(self, rhs: Self) -> Option<Self> { $mod_name::checked_add(self, rhs) }
            #[inline]
            fn n_checked_sub(self, rhs: Self) -> Option<Self> { $mod_name::checked_sub(self, rhs) }
            #[inline]
            fn n_saturating_add(self, rhs: Self) -> Self { $mod_name::saturating_add(self, rhs) }
            #[inline]
            fn n_saturating_sub(self, rhs: Self) -> Self { $mod_name::saturating_sub(self, rhs) }
            #[inline]
            fn n_saturating_mul(self, rhs: Self) -> Self { $mod_name::saturating_mul(self, rhs) }
            #[inline]
            fn n_checked_div(self, rhs: Self) -> Option<Self> { $mod_name::checked_div(self, rhs) }
            #[inline]
            fn n_saturating_div(self, rhs: Self) -> Self { $mod_name::saturating_div(self, rhs) }
            #[inline]
            fn n_to_signed_clamped(self) -> Self::Signed { $mod_name::to_signed_clamped(self) }
        }
    };
}

impl_native_signed_math!(native_math_i128, i128, u128, 128, NativeInt::u128_max());
impl_native_signed_math!(native_math_i64, i64, u64, 64, NativeInt::u64_max());
impl_native_unsigned_math!(native_math_u128, u128, i128, NativeInt::u128_max());
impl_native_unsigned_math!(native_math_u64, u64, i64, NativeInt::u64_max());
