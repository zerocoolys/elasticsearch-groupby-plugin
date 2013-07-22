# NELO ElastichSeach highlight plugin Change Log

## How to update this CHANGELOG

The following notations are used in this change log. Listed in the order of importance.

- **NEW** = *New Functionality*
This includes any changes which add new functionality to the application.
- **ENH** = *Enhancements*
The changes which do not introduce any new functionality but improve the functionality of existing features.
- **FIX** = *Bug Fixes*
The changes which fix bugs.
- **REF** = *Code Refactoring*
This includes only those code changes which do not alter the functionality of the application.
- **DOC** = *Updated Documentation*
Changes related to the documentation improvements. This includes the changes to `CHANGELOG.md` as well as `README.md`.
- **COM** = *Improved Comments*
Changes related to updated inline code comments.

## How to release a new version

Besides these, when a new version is released, **no need** to log about it in this `CHANGELOG.md` file because each new feature or bug fix is already logged here. However, when a new version is released, the following actions need to be performed:

1. Bump the version up in the `package.json` file.
2. Update this `CHANGELOG.md` file by replacing the `Unreleased` label with the release date.

        # The following:
        Version 1.2.0 (Unreleased)

## Version 1.2.0 (Unreleased)

- NEW: support elasticsearch 0.90.2 version.
- NEW: change the highlight implementation from Action to HighlightPlugin
- NEW: validate the field name to match the length less than 50 or only contains letter , digital ,'_', '-'
- NEW: change command to "_search" , and add a "type" field in "highlight" object , which value is "nelo-highlight"

## Version 1.1.0 (Jun 11, 2013)

- NEW: Support elasticsearch 0.20.6 version.
- NEW: Please use "http://IP:Port/index/type/_highlight" command to search.
- NEW: Support nelo2 webapp 1.1.6, 1.2.0 version.

