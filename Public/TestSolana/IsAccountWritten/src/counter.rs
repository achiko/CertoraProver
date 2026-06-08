use solana_program::{
    account_info::{next_account_info, AccountInfo},
    entrypoint::ProgramResult,
    program_error::ProgramError,
    pubkey::Pubkey,
};

mod rules;

// --------------------------------------------------------------------------
// Instructions
// --------------------------------------------------------------------------

/// Instruction 0 — write an initial count of 0 into the account.
fn initialize(_program_id: &Pubkey, accounts: &[AccountInfo]) -> ProgramResult {
    let account = next_account_info(&mut accounts.iter())?;
    check_writable_owner(account, _program_id)?;

    let mut data = account.try_borrow_mut_data()?;
    check_data_len(&data)?;

    let count: i64 = 0;
    data[..8].copy_from_slice(&count.to_le_bytes());

    Ok(())
}

/// Instruction 1 — add 1 to the counter.
pub fn add_counter(_program_id: &Pubkey, accounts: &[AccountInfo]) -> ProgramResult {
    let account = next_account_info(&mut accounts.iter())?;
    check_writable_owner(account, _program_id)?;

    let mut data = account.try_borrow_mut_data()?;
    check_data_len(&data)?;

    let count = read_count(&data)?;
    let new_count = count.checked_add(1).ok_or(CounterError::Overflow)?;
    data[..8].copy_from_slice(&new_count.to_le_bytes());

    Ok(())
}

/// Instruction 2 — subtract 1 from the counter.
fn decrement_counter(_program_id: &Pubkey, accounts: &[AccountInfo]) -> ProgramResult {
    let account = next_account_info(&mut accounts.iter())?;
    check_writable_owner(account, _program_id)?;

    let mut data = account.try_borrow_mut_data()?;
    check_data_len(&data)?;

    let count = read_count(&data)?;
    let new_count = count.checked_sub(1).ok_or(CounterError::Underflow)?;
    data[..8].copy_from_slice(&new_count.to_le_bytes());

    Ok(())
}

// --------------------------------------------------------------------------
// Helpers
// --------------------------------------------------------------------------

/// Deserialize the first 8 bytes of account data as a little-endian i64.
pub fn read_count(data: &[u8]) -> Result<i64, ProgramError> {
    let bytes: [u8; 8] = data[..8].try_into().map_err(|_| ProgramError::InvalidAccountData)?;
    Ok(i64::from_le_bytes(bytes))
}

/// The counter account must be writable and owned by this program.
fn check_writable_owner(account: &AccountInfo, program_id: &Pubkey) -> ProgramResult {
    if !account.is_writable {
        return Err(ProgramError::InvalidAccountData);
    }
    if account.owner != program_id {
        return Err(ProgramError::IncorrectProgramId);
    }
    Ok(())
}

/// The account must hold at least 8 bytes (one i64).
fn check_data_len(data: &[u8]) -> ProgramResult {
    if data.len() < 8 {
        return Err(ProgramError::AccountDataTooSmall);
    }
    Ok(())
}

// --------------------------------------------------------------------------
// Custom errors  (map to u32 via Into<ProgramError>)
// --------------------------------------------------------------------------

#[derive(Debug)]
enum CounterError {
    Overflow,
    Underflow,
}

impl From<CounterError> for ProgramError {
    fn from(e: CounterError) -> Self {
        match e {
            CounterError::Overflow  => ProgramError::Custom(0),
            CounterError::Underflow => ProgramError::Custom(1),
        }
    }
}
