# Release verification without publishing

Run the **Release** workflow manually on the branch to verify. Keep **dry_run**
enabled (the default) and set **tag_name** to `v` followed by the checked-out
`versionName`, for example `v1.2.0`. No new tag or version bump is needed for a
dry-run. The checkout is the selected workflow ref, not the tag_name input.

Dry-run runs the normal metadata checks, unit tests, lint, minified release build,
release-keystore signing, zipalign, APK/package/version/certificate verification,
checksum generation and release-notes preparation. The signed APK and
`SHA256SUMS.txt` are uploaded to the run's **release-assets** artifact. These are
real developer-signed test artifacts; they are not a published GitHub Release.

The workflow uses a read-only GitHub token, does not persist checkout credentials,
and does not pass `GH_RELEASE_TOKEN` to a dry-run. Temporary signing files are
removed even if the build fails. Existing signing secrets are still required.

Publishing requires explicitly disabling **dry_run**, or pushing a release tag
matching `v*`. Tag pushes retain the existing publishing behavior. Both publishing
paths require `GH_RELEASE_TOKEN`. The `draft` and `prerelease` inputs only affect
publication; dry-run takes precedence over them.

Direct Fastlane invocation also defaults to dry-run. Set `FASTLANE_DRY_RUN=false`
and provide `GITHUB_API_TOKEN` to explicitly enable publication. An invalid
`FASTLANE_DRY_RUN` value fails before building.

Keep `runs-on: ubuntu-latest` in main. To test a future runner, temporarily change
both CI and Release to that runner on a separate branch and dispatch both workflows
there, with `dry_run=true` for Release.
