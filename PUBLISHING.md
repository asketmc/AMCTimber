# Publishing AMCTimber

A one-time checklist for the steps only you can do (they need your accounts). After this, every release is
just `git tag`.

## 1. GitHub (source + automation)

```bash
cd AMCTimber
git init && git add -A && git commit -m "AMCTimber 1.0.0"
# create an empty repo named AMCTimber on github.com/asketmc, then:
git branch -M main
git remote add origin https://github.com/asketmc/AMCTimber.git
git push -u origin main
```

You do **not** strictly need GitHub to publish to Modrinth (you can upload the jar by hand). But it powers
the auto-publish workflow and gives the "Source" link that makes this a real open-source project.

## 2. Modrinth project

1. Create a project at <https://modrinth.com/> → **Create a project**.
   - Type: **Mod/Plugin** · Environment: **Server** · License: **GPL-3.0-only**
   - Slug: `amctimber` · Summary: "Valheim-style tree felling"
2. Paste **MODRINTH.md** into the Description. Add a falling-tree GIF at the top.
3. Set the **Source** link to your GitHub repo.
4. Either upload `target/AMCTimber-1.0.0.jar` manually for the first version, or let CI do it (step 4).

## 3. bStats (optional, recommended — see your adoption)

1. Register the plugin at <https://bstats.org/getting-started> → get a numeric **plugin id**.
2. Put it in `TimberPlugin.java` → `BSTATS_ID` and rebuild. (It stays disabled while the id is `0`.)

## 4. Automated releases (CI)

The workflow `.github/workflows/release.yml` builds and publishes to Modrinth + GitHub Releases on every
`v*` tag. Add two repo secrets first (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `MODRINTH_TOKEN` | a Modrinth PAT with write scope — <https://modrinth.com/settings/pats> |
| `MODRINTH_ID` | your project id/slug, e.g. `amctimber` |

Then release:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

CI builds the jar, uploads it to Modrinth (loaders: paper/purpur/folia; MC 1.20.6–1.21.x), and creates a
matching GitHub Release. Bump `<version>` in `pom.xml` + add a `CHANGELOG.md` entry before each new tag.

## 5. Reach (optional)

Mirror to [Hangar](https://hangar.papermc.io/) (Paper's official platform) for more discovery, and post in
CIS/RU Minecraft communities. Every server that installs it links back to asketmc.com — that's the point.
