# Repository rules for coding agents

- Commit as the repository owner. The author and committer must be TheCascadian <jedidonald03@gmail.com>. Never commit under any other identity.
- Never add Co-Authored-By trailers, session or tool trailers, "Generated with" lines, or any agent or vendor attribution to commit messages, pull request text, code comments or files.
- Never create or edit anything under `.github/` (no GitHub Actions, no CI workflows). Builds and checks run locally.
- Do not open pull requests. Push work to the branch you were given and stop.
- Do not change gameplay behaviour, data formats or recipes unless the task says so.
- Build with `./gradlew build`. Unit tests are under `src/test`.
