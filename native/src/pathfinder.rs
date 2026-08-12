//! Spotify's GraphQL gateway, which is where the personalised pages live.
//!
//! The access point answers about things that exist: a playlist, an album, the
//! account's own list of playlists. It does not answer about a person. "Made
//! for you", the daily mixes, the shelves that change through the day are put
//! together somewhere else, and the web client reads them from
//! `api-partner.spotify.com/pathfinder`.
//!
//! Two things make this different from every other call in this app, and both
//! are reasons to keep it at arm's length.
//!
//! The queries are persisted: the client does not send GraphQL, it sends a
//! sha256 of a query Spotify already knows, and those hashes change when the
//! web client is rebuilt. A hash that has been retired answers with an error
//! rather than data, so anything reading this has to survive getting nothing
//! back. The hashes below were read out of a network capture of the web player.
//!
//! And it is a different host from the rest, so it needs the tokens spelled out
//! rather than the access point's own plumbing.

use crate::engine;
use bytes::Bytes;
use http::{header::HeaderValue, Method, Request};
use librespot_core::Session;

const ENDPOINT: &str = "https://api-partner.spotify.com/pathfinder/v2/query";

/// The web player Spotify believes it is talking to.
///
/// Sent because the gateway expects a client to say what it is; the values are
/// the web player's own, since that is the client these queries belong to.
const APP_PLATFORM: &str = "WebPlayer";

/// The web client's own version string, as captured from it.
///
/// Sent because the gateway is choosy about who is asking: with the platform
/// alone the home query answered `GenericError`, which is what it says to a
/// caller it does not recognise rather than to one it refuses. Like the query
/// hashes, this ages: see the note at the top of the file.
const APP_VERSION: &str = "1.2.97.155.g5dd0dcaf-development";

/// One persisted query, by name and hash. See the note at the top of the file.
struct Query {
    name: &'static str,
    hash: &'static str,
}

/// The personalised home: the shelves, in the order the account is meant to see
/// them.
const HOME: Query = Query {
    name: "home",
    hash: "76243c78b0e20ecdbe41b794dec8cbe73f75e585b0a7201b8d2e84578412847a",
};

/// Runs one persisted query and returns the raw JSON body.
async fn query(
    session: &Session,
    query: &Query,
    variables: String,
    language: &str,
) -> engine::EngineResult<String> {
    let body = format!(
        concat!(
            "{{\"operationName\":\"{}\",\"variables\":{},",
            "\"extensions\":{{\"persistedQuery\":{{\"version\":1,\"sha256Hash\":\"{}\"}}}}}}"
        ),
        query.name, variables, query.hash
    );

    let token = session
        .login5()
        .auth_token()
        .await
        .map_err(|e| format!("no access token: {e}"))?;

    let mut request = Request::builder()
        .method(&Method::POST)
        .uri(ENDPOINT)
        .body(Bytes::from(body))
        .map_err(|e| format!("bad request: {e}"))?;

    {
        let headers = request.headers_mut();
        headers.insert(
            http::header::CONTENT_TYPE,
            HeaderValue::from_static("application/json;charset=UTF-8"),
        );
        headers.insert(
            http::header::ACCEPT,
            HeaderValue::from_static("application/json"),
        );
        headers.insert("app-platform", HeaderValue::from_static(APP_PLATFORM));
        // The shelves are titled by the server, and the greeting with them, so
        // the page arrives in English unless this says otherwise.
        if let Ok(value) = HeaderValue::from_str(language) {
            headers.insert(http::header::ACCEPT_LANGUAGE, value);
        }
        headers.insert("spotify-app-version", HeaderValue::from_static(APP_VERSION));
        headers.insert(
            http::header::ORIGIN,
            HeaderValue::from_static("https://open.spotify.com"),
        );
        headers.insert(
            http::header::REFERER,
            HeaderValue::from_static("https://open.spotify.com/"),
        );
        headers.insert(
            http::header::AUTHORIZATION,
            HeaderValue::from_str(&format!("{} {}", token.token_type, token.access_token))
                .map_err(|e| format!("bad token: {e}"))?,
        );
        // Without this the gateway answers 401 whatever the access token says:
        // it identifies the client, not the listener.
        match session.spclient().client_token().await {
            Ok(client_token) => {
                if let Ok(value) = HeaderValue::from_str(&client_token) {
                    headers.insert("client-token", value);
                }
            }
            Err(e) => log::warn!("pathfinder without a client token: {e}"),
        }
    }

    let response = session
        .http_client()
        .request_body(request)
        .await
        .map_err(|e| format!("{} failed: {e}", query.name))?;

    String::from_utf8(response.to_vec()).map_err(|e| format!("unreadable answer: {e}"))
}

/// A stable stand-in for the web client's visitor cookie.
fn visitor_id() -> String {
    // Derived from the device id so it does not change between launches, and
    // means nothing outside this call.
    let id = engine::with_session(|session| session.device_id().to_string()).unwrap_or_default();
    let digest: u128 = id
        .bytes()
        .fold(0u128, |acc, b| acc.wrapping_mul(31).wrapping_add(b as u128));
    format!("{digest:032x}")
}

/// The account's home page, as Spotify's own client receives it.
pub fn home(time_zone: &str, language: &str) -> engine::EngineResult<String> {
    let handle = engine::runtime_handle()?;
    let session = engine::with_session(|session| session.clone())?;

    // The time zone decides what "good evening" and the daily mixes are, and
    // the gateway wants it named rather than guessed from an offset.
    let zone = if time_zone.is_empty() {
        "Europe/Rome".to_string()
    } else {
        time_zone.to_string()
    };
    let language = if language.is_empty() { "en" } else { language }.to_string();
    // `sp_t` is the web client's own visitor id, a cookie it sends with every
    // one of these. Sent here as a fixed value derived from nothing, because
    // what it identifies is a browser this app does not have; whether the
    // gateway cares is exactly what this is for.
    let variables = format!(
        concat!(
            "{{\"homeEndUserIntegration\":\"INTEGRATION_WEB_PLAYER\",\"timeZone\":\"{}\",",
            "\"sp_t\":\"{}\",",
            "\"facet\":\"\",\"sectionItemsLimit\":10,\"includeEpisodeContentRatingsV2\":true}}"
        ),
        zone, visitor_id()
    );

    handle.block_on(async move { query(&session, &HOME, variables, &language).await })
}
