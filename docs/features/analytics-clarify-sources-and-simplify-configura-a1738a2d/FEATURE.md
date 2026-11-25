# Analytics: clarify sources and simplify configuration

Design memory of a Mooi software factory feature, kept by the platform. Its live state (task statuses,
sessions, assignments) lives in Mooi; each task's technical decisions are in `decisions/`.

## Item

`a1738a2d-e8ec-4a2d-9135-a91a195fa558`

## Goal and spec

## Goal
Make Analytics understandable by identifying every chart/table source, explaining Statistics vs SEO vs GEO and the Mooi tracker, moving setup to a configuration modal, and showing only five Search Console top queries initially with Load more.

## User-facing behaviour
- Clearly distinguish Statistics (Mooi first-party tracker, plus separately labelled optional GA4), SEO (organic Google Search Console performance), and GEO (AI referral visits attributed by tracker referrers, separate from prompt-based AI visibility runs and the SEO/GEO audit).
- Every chart/table identifies its actual data source, including overview, traffic, clicks, GA4, Search Console and GEO reports.
- Replace inline setup with an accessible responsive configuration modal containing Mooi snippet, detailed tracker explanation/privacy, allowed origins, key regeneration and Google account/property selection. Preserve existing save/error/OAuth behavior and setup discoverability when there are no events.
- Explain tracker records production page views/clicks, supports referrer/UTM/device and live/aggregate reports, classifies AI referral traffic, does not provide Google ranking data or execute AI visibility prompts, follows existing privacy behavior, and never measures development previews.
- Top queries initially show five rows; Load more reveals incremental batches from fetched data until exhausted. Sorting works and accessible visible/total counts update. Pages remain unchanged. Preserve date range, partial errors, responsive layout and dark mode.

## Technical decisions
Implement only in spa-mooi/src/features/analytics, reuse current APIs and shared Modal, no backend changes. Query expansion is client-side because the current endpoint returns a bounded set; do not imply server pagination. QA independently verifies integrated source labels, category explanations, modal behavior, five-row/load-more/sorting behavior and mobile layout.

## Tasks

| Key | Title | Category | Depends on | Acceptance criteria |
| --- | --- | --- | --- | --- |
| analytics-clarity | Clarify Analytics sources, explain tracker, modalize setup and add query Load more | UX/UI | - | Every visualization/report has accurate visible source attribution; Statistics/SEO/GEO and tracker purpose/limits/privacy are clear; tracker and Google setup remain fully functional in an accessible configuration modal; Search Console queries show five initially and Load more incrementally through fetched results, updates accessible counts, retains sorting; Pages unchanged; desktop/mobile, dark mode, empty/error states work. |
