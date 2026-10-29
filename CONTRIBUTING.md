# Contributing to Event Board

## Production branch

`main` is the production branch. It must always be ready to deploy. Nothing is committed
or pushed to `main` directly. Every change reaches `main` through a pull request.

## Workflow

Fixes and features follow the same path:

1. **Open or pick an issue.** Every change starts from a GitHub issue.
2. **Acknowledge the issue before starting.** Comment on the issue with your understanding
   of the problem, the likely cause (marking confirmed findings separately from
   assumptions), the proposed fix or implementation, how you will test it, and any
   dependency or clarification needed. Update the comment if the approach changes.
3. **Start from an up-to-date `main`.**

   ```bash
   git switch main
   git pull --ff-only origin main
   ```

4. **Create a branch** named after the issue:
   - bug fix: `fix/<issue-number>-<short-description>`, e.g. `fix/1-editor-auth`
   - feature: `feat/<issue-number>-<short-description>`, e.g. `feat/4-docker-support`
5. **Keep the branch focused.** One issue per branch. Put unrelated work on separate
   branches and pull requests.
6. **Validate the change.** Compile the server (`javac --release 11 DakBoard.java`) and
   test the behavior you changed. Describe what you tested in the pull request.
7. **Open a pull request into `main`.** Fill in the pull request template: link the
   issue, summarize the change, and list the testing performed. Use `Closes #<number>`
   when the pull request fully resolves the issue.
8. **Pass the required checks and get a review** before merging. Only merge changes that
   are ready for production.
9. **Delete the branch** after it is merged.

## Rules for `main`

- No direct pushes and no force pushes to `main`.
- Changes arrive only through pull requests.
- Required CI checks will be added once CI (#3) is in place.

## Dependencies

If your work depends on another issue, say so in the issue and the pull request, and
note the merge order.

## Files that stay out of git

Instance data is not committed: `settings.json`, `clients/`, `photos/` uploads,
credential files (`admin-credential*.json`, `editor-credential*.json`,
`admin-credentials.txt`), and `events.cache.json`. See `.gitignore`.
