# Telegram release announcements

The `Telegram release` workflow announces a release after its GitHub draft is
published. It posts the full release notes, followed by the ARM64, ARMv7 and
universal APKs, to both `@SquareAppOfficial` and the community's News & Releases
topic. It checks that all three attachments are uploaded before posting.
GitHub headings and bold text become Telegram bold, links are clickable,
and inline code and fenced code blocks retain their formatting.

## One-time setup

1. Create a dedicated bot with Telegram's verified `@BotFather` (`/newbot`).
2. Add it to `Square | Official` as an administrator with **Post messages**.
   It does not need permission to add administrators or delete messages.
3. Add it to `Square | Community` as an administrator, allowing messages and
   documents and **Manage topics**, so it can post in the closed announcement
   topic. It does not need permission to add administrators or delete messages.
4. In the repository's Settings → Secrets and variables → Actions, create the
   repository secret `TELEGRAM_BOT_TOKEN`. Paste the BotFather token there,
   never into source files, issues or chat messages.
5. Confirm these repository variables, or use their workflow defaults:

   | Variable | Default | Purpose |
   | --- | --- | --- |
   | `TELEGRAM_CHANNEL` | `@SquareAppOfficial` | Public announcement channel |
   | `TELEGRAM_GROUP` | `-1003964165935` | Square community supergroup |
   | `TELEGRAM_THREAD_ID` | Empty | Optional forum topic ID |

   News & Releases is currently the renamed **General** topic, so leave
   `TELEGRAM_THREAD_ID` empty. If announcements move to a regular topic later,
   set its message thread ID instead. The group ID is derived from its Telegram
   Web link; verify the bot's access with the first manual run.

## Verification and publishing

Run `Telegram release` from Actions with the tag of an existing published
release and **dry_run enabled**. This previews the exact notes, filenames and
destinations without contacting Telegram or requiring the token.

Once the bot and secret are configured, disable dry_run to publish that release
and verify that notes and all three APKs appear in both destinations. Future
releases publish automatically on the GitHub `release.published` event.
Pre-releases also trigger this event. Drafts and ordinary CI builds do not.

Do not rerun a successful announcement: rerunning sends it again. If a run
fails after some messages were sent, check Telegram before rerunning to avoid
partial duplicate announcements. Sending to two destinations is not atomic.

To correct formatting without duplicating attachments, run the workflow with
`edit_messages` set to `[channel_message_id, group_message_id]` and dry_run
disabled. This edits only the existing notes, and supports releases whose notes
fit in one message. New announcements log their notes message IDs. Leave this
input empty for normal publishing.

If publication is later automated using GitHub's default `GITHUB_TOKEN`, that
publication will not trigger another workflow. Publish with an appropriate
user/GitHub App token, or explicitly dispatch this workflow after publication.

## Local checks

```sh
python3 -m unittest discover -s scripts/tests -v
```

The script uses Python's standard library only. Bot API calls avoid printing
credential-bearing URLs, and the token is held only in the GitHub secret and
runner environment.
