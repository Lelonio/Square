use std::io::Write;

use byteorder::{BigEndian, WriteBytesExt};
use protobuf::Message;
use thiserror::Error;

use crate::{Error, packet::PacketType, protocol};

#[derive(Debug, PartialEq, Eq)]
pub enum MercuryMethod {
    Get,
    Sub,
    Unsub,
    Send,
    // LOCAL CHANGE: Spotify's event service — the one the listening history is
    // built from — takes a POST carrying header fields, and neither the method
    // nor the fields could be expressed with the upstream request type.
    Post,
}

#[derive(Debug)]
pub struct MercuryRequest {
    pub method: MercuryMethod,
    pub uri: String,
    pub content_type: Option<String>,
    pub payload: Vec<Vec<u8>>,
    /// LOCAL CHANGE: header fields, as the event service requires.
    pub user_fields: Vec<(String, Vec<u8>)>,
}

#[derive(Debug, Clone)]
pub struct MercuryResponse {
    pub uri: String,
    pub status_code: i32,
    pub payload: Vec<Vec<u8>>,
}

#[derive(Debug, Error)]
pub enum MercuryError {
    #[error("callback receiver was disconnected")]
    Channel,
    #[error("error handling packet type: {0:?}")]
    Command(PacketType),
    #[error("error handling Mercury response: {0:?}")]
    Response(MercuryResponse),
}

impl From<MercuryError> for Error {
    fn from(err: MercuryError) -> Self {
        match err {
            MercuryError::Channel => Error::aborted(err),
            MercuryError::Command(_) => Error::unimplemented(err),
            MercuryError::Response(_) => Error::unavailable(err),
        }
    }
}

impl std::fmt::Display for MercuryMethod {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match *self {
            MercuryMethod::Get => "GET",
            MercuryMethod::Sub => "SUB",
            MercuryMethod::Unsub => "UNSUB",
            MercuryMethod::Send => "SEND",
            MercuryMethod::Post => "POST",
        };
        write!(f, "{s}")
    }
}

impl MercuryMethod {
    pub fn command(&self) -> PacketType {
        use PacketType::*;
        match *self {
            MercuryMethod::Get | MercuryMethod::Send | MercuryMethod::Post => MercuryReq,
            MercuryMethod::Sub => MercurySub,
            MercuryMethod::Unsub => MercuryUnsub,
        }
    }
}

impl MercuryRequest {
    pub fn encode(&self, seq: &[u8]) -> Result<Vec<u8>, Error> {
        let mut packet = Vec::new();
        packet.write_u16::<BigEndian>(seq.len() as u16)?;
        packet.write_all(seq)?;
        packet.write_u8(1)?; // Flags: FINAL
        packet.write_u16::<BigEndian>(1 + self.payload.len() as u16)?; // Part count

        let mut header = protocol::mercury::Header::new();
        header.set_uri(self.uri.clone());
        header.set_method(self.method.to_string());

        if let Some(ref content_type) = self.content_type {
            header.set_content_type(content_type.clone());
        }

        for (key, value) in &self.user_fields {
            let mut field = protocol::mercury::UserField::new();
            field.set_key(key.clone());
            field.set_value(value.clone());
            header.user_fields.push(field);
        }

        packet.write_u16::<BigEndian>(header.compute_size() as u16)?;
        header.write_to_writer(&mut packet)?;

        for p in &self.payload {
            packet.write_u16::<BigEndian>(p.len() as u16)?;
            packet.write_all(p)?;
        }

        Ok(packet)
    }
}
