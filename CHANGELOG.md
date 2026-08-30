# Changelog

All notable changes to Cirrus are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Nothing yet.

## [2.2.0] - 2026-08-30

Scratch files stop being treated as rubbish. The premise that made them
disposable — that nobody could see them — stopped being true in 2.1.0, and this
is the rest of that change catching up.

### Added

- **A "clear scratch files after" setting, and it defaults to never.** These files were swept on an
  idle timer and wiped on every app start back when nothing could show them, which made
  "disposable" a decision taken on your behalf about work you had never been shown. Now that there
  is a Files screen and a download button they are yours, so nothing is cleared on a clock you did
  not set. Never, a day, a week or a month, under Settings → Tools.

  Two things happen whatever it says, and the help text says both: a scratchpad whose conversation
  you have deleted goes with it, since nothing could reach it any more; and a total size cap still
  applies as a last resort, because filling the device is the one outcome worse than losing a
  scratch file.

### Changed

- **Files is a button in the chat's top bar**, not an item in the three-dot menu. A feature whose
  entire point is that you can finally see something should not be two taps deep behind a menu.

- **An open reasoning trace shows how the thinking started, not where it has got to.** 2.1.0 showed
  the tail on the theory that somebody watching a model think wants to see it thinking. In practice
  the newest tokens are the middle of a thought, out of context by construction; the first few
  hundred words are where a model states the problem, notices the constraint everybody missed and
  picks an approach — which is the part worth reading, and the part that explains the answer when it
  arrives. It also happens to be free: the opening stops changing once the trace passes the cap, so
  there is nothing left to re-render, where a tail window was still laying out two thousand
  characters dozens of times a second. The whole trace is still there once it finishes.

- **An open reasoning trace shows how the thinking started, not where it has got to.** 2.1.0 showed
  the tail on the theory that somebody watching a model think wants to see it thinking. In practice
  the newest tokens are the middle of a thought, out of context by construction; the first few
  hundred words are where a model states the problem, notices the constraint everybody missed and
  picks an approach — which is the part worth reading, and the part that explains the answer when it
  arrives. It also happens to be free: the opening stops changing once the trace passes the cap, so
  there is nothing left to re-render, where a tail window was still laying out two thousand
  characters dozens of times a second. The whole trace is still there once it finishes.

## [2.1.1] - 2026-08-30

### Changed

- **`save_file` is behind the write switch.** It shipped in 2.1.0 as though it were not a write, on
  the reasoning that a file in your Downloads is the thing you asked for and deleting it is
  something you can do without us. That argues from what was probably wanted, and the switch does
  not: what makes something a write here is that the effect outlives the turn, happens outside
  Cirrus, and cannot be undone by calling the same tool again. Saving a file meets all three — it
  lands in shared storage, which on Android outlives Cirrus being uninstalled, and calling it twice
  produces `totals (1).csv` rather than taking the first copy back.

  So it now needs **Settings → Tools → Allow write actions**, which is off by default. A model asked
  for a file with the switch off is refused and told which switch it is, so what you see is an offer
  to turn it on rather than a failure — and it is told to put the contents in its answer as well, so
  the work is not lost either way. The Files screen's own download button is unaffected: that is you
  pressing it, not the model.

## [2.1.0] - 2026-08-30

### Added

- **A file browser for the scratchpad, and a viewer that knows what it is looking at.** The shell
  has been able to write files since it shipped, and read them back, and list them — and the person
  whose device they were on could not do any of that. "I saved the totals to expenses/totals.csv"
  was a true sentence about a file with no screen in the app showing it, so the only way to see your
  own working file was to ask the model to print it back at you one `cat` at a time.

  **Files** in a conversation's overflow menu opens what that thread's commands have written,
  grouped by the job it belongs to rather than as one flat list, because the useful question is
  "what did the expenses work leave behind" and not "what files exist". Tapping one opens it, and
  the viewer renders by format rather than dumping text: markdown as prose, CSV and TSV as a table
  with quoted fields and embedded commas intact, JSON, HTML, YAML and source highlighted, images
  decoded, plain text and logs in monospace, and an honest "nothing to show, download it to open it
  elsewhere" for anything binary. Files with no extension — which the shell produces constantly —
  are read as text unless their bytes say otherwise.

  **Every file has a download button**, going to the same place `download_file` does: your
  Downloads folder, where you can open it with something that understands it. Which is the point.
  Alongside it, copy for anything textual, and delete for a file, a job, or the lot.

- **`save_file`, so the model can actually give you a file it made.** This was the other half of
  the same gap, and the more embarrassing one: `run_command` could write a file and the model could
  read it back, so it would say "I've saved the totals to expenses/totals.csv" — a sincere offer
  that could not be accepted, because the workspace is inside Cirrus's private storage and there
  was no tool for getting anything out of it. There is now, and the shell's instructions say
  plainly that a path in the workspace is not an answer to give a user. It copies to Downloads,
  renames on the way out if the working name was for the model rather than for you, and reports the
  name it actually landed under.

### Changed

- **Reasoning traces stay collapsed while they are being written.** They used to open themselves,
  on the theory that a model thinking should have something to watch. Two things were wrong with
  that. A trace is often several times longer than the answer it precedes, so the transcript filled
  with text nobody had asked to read and the answer arrived below the fold. And it was expensive in
  a way that got worse the longer the model thought: the trace is one text block, it grows by a
  token every few dozen milliseconds, and each of those re-measured and re-laid-out the whole of it
  — a thousand tokens of reasoning is a thousand layout passes over a paragraph that is a thousand
  tokens long by the end. The visible symptom was a transcript that stuttered and jumped.

  Collapsed, none of that happens: the text is not rendered at all until you ask for it, and the
  trace is still complete when you do. Open it mid-stream and you get the tail — what the model is
  considering now, which is the part anybody watching wants — with the whole of it once it lands.
  The transcript also no longer chases a box that is not changing height.

## [2.0.1] - 2026-08-30

Five fixes, and four of them have the same shape: Cirrus did something
defensible, said nothing about it, and the result was indistinguishable from
the app losing your work.

### Fixed

- **A downloaded file now reaches you.** `download_file` saved only into the shell's scratch
  workspace, which lives inside the app's private storage — so "downloaded to expenses/report.csv"
  described a file you had no way to open. That is worse than a failure, because a failure would at
  least have been actionable. The file now also lands in your Downloads folder, through MediaStore
  on Android and `~/Downloads` on the desktop, and the model is told the name it was saved under so
  it can tell you. The working copy still goes to the scratchpad, since that is the only place the
  shell can read it, and a model fetching a page purely for its own analysis can pass `save=false`
  to keep it out of your way.

- **The shell can edit text, and now says so.** Every listed program could always write — the
  workspace is the only place any of them can reach — but the one-line summary the model reads split
  them into "read-only" and "writes" and put `sed` on the wrong side. Asked to change a line in a
  file it had just written, a model concluded it had no way to do it, when `sed -i` had been allowed
  the whole time. The summary now says what the check actually enforces, the tool description gives
  the recipes for an in-place edit and a read-and-rewrite, the command length cap goes from 500 to
  1,200 characters (a `sed` with three substitutions and a filename is three hundred before anything
  unusual has happened), and `fmt`, `column`, `expand`, `unexpand`, `pr`, `split`, `csplit`,
  `numfmt`, `iconv`, `tsort` and `realpath` join the list.

- **Scratch files stop disappearing mid-job.** The workspace was swept before *every* command, with
  a forty-five-minute idle window — so a topic that crossed that line between two steps of one job
  vanished underneath the model, which from the transcript is indistinguishable from the app losing
  a file. The sweep is now rate-limited to once every thirty minutes with a day's idle window, so no
  job of a realistic length spans one at all. The process-start wipe is gone too: starting the app
  used to clear everything, so a conversation picked up the next morning had lost what it was
  working on. What is dropped at startup now is only what cannot belong to anything — scratchpads
  whose conversation has been deleted, and anything untouched for a week.

- **Each conversation gets its own scratchpad.** Topics were global, so two threads both working in
  a topic called "notes" wrote into the same directory: one thread's files turned up in the other's
  listing, and `clean_workspace` in either took both. Files are now scoped to the conversation that
  made them, which also means a thread's scratch work goes when the thread does.

- **Agents fire when they are supposed to.** Two separate bugs, one per platform, with the same
  symptom.

  On Android the clock was WorkManager, which is designed to defer: under Doze it holds a job for
  hours to line it up with a maintenance window, and a phone left alone overnight is in Doze at
  exactly the moment a morning agent is due. The briefing arrived whenever the phone was next picked
  up. Agents are now booked as alarms, which Doze does not hold, with WorkManager still running the
  generation once the alarm has fired. Alarms do not survive a reboot the way WorkManager's queue
  did, so they are re-booked on boot and after an update. This also fixed a second fault underneath:
  the worker re-booked itself under the same unique work name it was running as, with a policy that
  cancels running work, so it was cancelling itself on the way out.

  On the desktop the wait was a `delay` for a duration, and `delay` is measured on a monotonic clock
  that does not advance while a laptop is suspended. An agent booked at 23:00 to fire in eight and a
  half hours still believed it had eight and a half hours to go when the lid opened at 07:45, and
  went off some time that afternoon — every agent scheduled overnight, which is most of them. The
  wait is now against the wall clock, checked once a minute, so a machine that slept through the
  moment notices as soon as it wakes. A run up to six hours late is still taken; anything older is
  skipped, because yesterday's briefing is not this morning's.

## [2.0.0] - 2026-08-29

The major number moves because the model gains a new kind of capability rather than another tool:
it can now be handed somebody else's method for a job. Everything else here is a correction to
something the app was already doing and doing badly — reading an answer aloud in full, and letting a
model spend a turn discovering it cannot build a website on a phone.

### Added

- **Skills, and a library to install them from.** A skill is a page of instructions for one kind of
  job, written by somebody who does it, published to the public registry at
  [skills.sh](https://skills.sh) — the index behind `npx skills` — and installed into Cirrus from a
  new **Settings → Skills** screen with an Explore page beside it.

  The design that matters is the chooser. Only the *names and one-line descriptions* of enabled
  skills go into the system prompt; the instructions themselves arrive when the model calls
  `use_skill`, having decided this is the job. Putting every installed skill's body in the prompt
  would cost thousands of tokens on every turn of every conversation, nearly all of it about work
  nobody is doing — so a description is the advertisement, the body is the cost, and nothing pays
  the cost until it is worth paying. `list_skills` searches a longer library than the brief lists.

  Two things follow from these being written by strangers, for a different program. Most of the
  registry assumes a coding agent with a terminal and a checkout, so the instructions arrive
  attached to a sentence saying to take the method and ignore everything that assumes a development
  machine — attached to them, because a rule in the system prompt is read before the skill and
  forgotten by the time it contradicts one. And a skill can tell the model to do something but
  cannot make it possible: every existing gate still applies, so a skill reaching for a write action
  gets the same refusal any other route would.

  The Explore page opens on curated subject chips rather than an empty search box. That is the
  registry's shape rather than a flourish: it has no endpoint that lists everything and rejects a
  query under two characters, so the chips are ordinary searches through the same call — no second
  data path, and no hand-kept list that can go stale. Nothing installs on a single tap; the card
  opens a preview with the real `SKILL.md` in it first, alongside the install count, which is the
  only signal of trust the registry offers.

  Reference files a package ships are named and not downloaded. Cirrus can neither fetch them on
  demand nor execute anything, so a skill whose instructions say "see `references/testing.md`" is
  told, at the moment it is loaded, that the file is not here — otherwise the model goes looking and
  spends a turn concluding something is broken.

- **`download_file`**, which fetches a URL's actual bytes into the shell workspace. `web_fetch`
  flattens a page to prose, which is right for "what does this article say" and wrong for
  everything else: the markup is gone, and so is the CSV's comma structure and the JSON. The file
  lands in the topic the model is already working in, so `grep`, `wc` and `head` are right there and
  it can be looked at three times without being fetched three times. It reads in bounded chunks
  against the topic's own budget rather than a number of its own — on a phone, from a URL a model
  chose, holding a whole file in memory before anything can decide it is too large is the one
  failure worth extra code to avoid.

### Changed

- **Read aloud speaks a summary of a long answer, not the whole of it.** Speech is linear and runs
  at about two and a half words a second, so an answer written to be skimmed — headings to jump
  between, a table to glance at, a code block to ignore — is six minutes of audio with no way to
  skip the part you did not need. People pressed play and stopped it a minute in.

  A long answer is now condensed by the model you are already using into two or three spoken
  paragraphs: what it concluded, the reasoning that matters, anything you would be misled by not
  hearing, and a closing line saying the full answer is on screen. Short answers are still read word
  for word, because summarising four sentences produces three, more slowly, having lost something.
  Every failure — no model configured, a request past its deadline, an empty reply — falls back to a
  local extract of the opening and the closing, so the button always makes sound. **Voice → How much
  to read** switches back to the whole answer, for the one case a summary cannot serve: listening to
  your own text to check it.

- **The shell says no to building things, and says why.** A model that has decided to build a
  website reached for `npm`, was told it was "not available" alongside a list of what was, read that
  as an inventory problem, and tried `yarn`, then `pnpm`, then wrote a `package.json` by hand.
  Compilers, package managers, runtimes, web servers and container tools are now refused by name
  with the reason the plan cannot work at all — no toolchain on a phone and, since Android 10, no
  way to install one — and with what to do instead: put the file's contents in the answer, where
  the user can read and copy them. The same rule is stated once in the system prompt, because every
  refusal otherwise costs a round trip to discover, and a plan abandoned six commands in has already
  spent the turn.

- **A scratch topic has a size, and it is announced before it is enforced.** Past a couple of
  megabytes or forty files, the next command in that topic is refused with the topic named and
  `clean_workspace` pointed at. The workspace already had a total cap, but it enforced it by
  deleting oldest-first across every topic — so the price of one runaway command was another job's
  working files, paid silently. Every command now reports how much of the budget its topic has used,
  and warns past half: a number that first appears in a refusal is a surprise, and a number watched
  climbing for three commands is a budget.

## [1.9.0] - 2026-08-23

### Added

- **The desktop app has an icon.** A packager given no `iconFile` does not fail — it quietly ships
  the generic Java coffee cup, which is what Cirrus had been wearing in the Dock, the taskbar and
  every alt-tab list. The mark is rendered from the same geometry as Android's launcher icon, in
  the three containers the platforms each insist on. macOS needs it twice over: the Dock reads the
  icon from the application bundle, which a `:desktop:run` has none of, so it is also handed over
  directly through `Taskbar`.

### Changed

- **Desktop settings are nine groups and two destinations, not one long scroll.** The old page was
  ordered by when each control was written, which put the context-window field below the GitHub
  token. That a 1180pt window can render thirty unrelated controls at once was the argument for
  leaving it alone, and it was the wrong one — a window wide enough to show everything is not a
  window anybody can scan. The groups, their order and their names are the phone's, so nobody with
  both builds learns where things live twice, and `describe_settings` can hand the model
  "Settings → Tools → Memory" without knowing which machine it is answering on.
- **The settings controls themselves now match the phone's**, which is where the desktop had
  quietly invented its own:
  - The **connection** section leads with the API key and follows with the host, and each has its
    own commit. One "Save and test" button meant a mistyped host could not be corrected without
    re-entering a key. Testing now reports which model answered rather than a bare "connected" —
    a host with an empty catalogue says the same thing otherwise.
  - **Every secret field can be revealed.** A key is pasted far more often than it is typed, and a
    permanently masked field gives no way to see that the wrong thing was pasted. What is revealed
    is only ever what has just been typed; the stored secret is never read back into the field.
  - **Spotify's redirect URI gets a panel and a copy button** instead of a mention inside a
    paragraph. It has to match on both ends character for character, it is invisible from Spotify's
    side, and getting it wrong produces an error on Spotify's own page that never mentions Cirrus.
    It is also longer here than on the phone — a loopback address with a port, not a tidy
    `cirrus://` scheme — so it is the one string on that screen nobody should retype by eye.
  - **The synthesis-model picker is a segmented row** with a sentence under it, not a dropdown. The
    choice is a trade between latency and delivery, and a menu showing one label at a time is
    exactly the control that hides a trade.


## [1.8.0] - 2026-08-20

### Added

- **The desktop window takes over its own title bar.** macOS was painting a grey system bar over a
  near-black app; it now stays transparent while the traffic lights remain, and every screen insets
  past the strip rather than drawing into it.
- **A real menu bar**, with Cmd+N, Cmd+, and Cmd+\ as its own — a menu shortcut is consumed before
  a root-level key handler ever sees it, so those three could not also live anywhere else.
- **The conversation list is a resident sidebar above 880pt of window width**, and a drawer below
  it, since a window can be dragged across that line at any moment.
- **Model picker, parameters and agent editors open as centred panels**, not bottom sheets — a
  sheet rising from the bottom edge answers a thumb on a phone, not a pointer in a window.
- Settings, chat and onboarding now cap their line length instead of stretching a settings row a
  hand's width from its label.
- A desktop-only **About** section in Settings names the data folder and offers to open it.

## [1.7.0] - 2026-08-17

### Added

- **Cirrus runs on the desktop.** A Compose Multiplatform build for macOS, Linux and Windows, from
  the same codebase as the Android app. Everything from `ChatEngine` inwards is the same code — the
  turn protocol, the tool registry and every gate on it, the shell policy, the memory retriever, the
  markdown and typeset maths, and the GitHub, Spotify and MCP integrations — so the two builds
  cannot drift in the parts that decide what the model may do.

  The platform edges are replaced rather than approximated. Persistence is a JSON file per store
  instead of Room and DataStore; the dependency graph is wired by hand instead of by Hilt; screen
  state is a plain class scoped to the composition instead of a `ViewModel`, since a window has no
  back stack for one to survive; and the schedulers are coroutines that sleep until due instead of
  WorkManager requests. Read-aloud plays raw PCM through `javax.sound.sampled`, because the JVM
  decodes no MP3 and shipping a decoder to avoid asking ElevenLabs for PCM would be the wrong trade.
  Spotify signs in over a loopback redirect, since a desktop app cannot claim a URI scheme.

  Three things do not come across, and are absent from the settings catalogue as well as the tool
  registry so the model is never told about a capability it does not have: dictation, location, and
  `media_control`.

  One behaviour genuinely differs and cannot not: **scheduled agents only run while Cirrus is
  open.** WorkManager persists its queue, so a sleeping phone fires a missed 07:30 late; a desktop
  app that was not running missed it, and the next occurrence is booked instead.

  The desktop build carries its own test suite — 416 tests — and CI now runs it on every pull
  request.

### Changed

- Releases now carry a package per desktop platform alongside the signed APK, each with a `.sha256`
  beside it. The desktop packages are **not** code-signed; the checksum is the integrity check.

## [1.6.0] - 2026-08-15

### Added

- **Text goes into a shell command through stdin now, rather than being quoted into it.**
  `run_command` takes an `input` argument, and it is the difference between counting the words in a
  paragraph and writing a `printf` puzzle with three ways to fail before the command runs: the
  command line is length-capped, and a `$` or a backtick anywhere in the text is refused by the
  substitution check. `wc -w`, `sort | uniq -c | sort -rn`, `sha256sum` and `tee` now work on text
  out of the conversation with no escaping at all.
- **Scratch files are organised by topic.** Each job — `expenses`, `log-counts` — gets its own
  directory, named by the `topic` argument and reused across the commands of that job. One flat
  scratch directory across a long session became `out.txt`, `out2.txt`, `tmp.txt`, and the model
  started reading the wrong one. Because `..` is refused, a topic isolates as well as organises: a
  command working on one job cannot touch another's files even by accident. `clean_workspace` takes
  a topic too, so finishing a job no longer means clearing everything or clearing nothing.
- **The workspace cleans itself up.** Topics nothing has touched for 45 minutes are swept before the
  next command, and there is a cap on how many can be live at once — the second rule is the one that
  matters, because a session that opens a fresh topic every few minutes stays inside the idle window
  forever. A sweep reports what it took, so a file that is no longer there is a sentence the model
  can act on rather than a puzzle it spends a turn on.
- `join`, `shuf` and `sha512sum` join the allowed programs, and every command's reply now lists the
  files in its topic — the next command is nearly always about one of them, and the alternative was
  a round trip spent on `ls`.

### Changed

- **A turn's tool calls collapse into one panel.** A turn that read three files and searched twice
  put five outlined cards between the question and the answer, each as prominent as the reply
  itself. That is the wrong weight: tool calls are provenance, and provenance belongs behind one
  line you can open. The panel says how many steps ran, which tools they used and how long they
  took, is open while they run and shut once the answer lands, and shows a failure on its own line
  so nothing is hidden that matters. A single call is still a single row — wrapping one call in a
  group header only means reading its name twice.
- **Long command output keeps both ends.** Nearly every text job here finishes with its answer — a
  `wc` after a pipeline, the last hunk of a diff, the tail of a sort — so a cap that kept the first
  8,000 characters threw away the line the command was run for, and the model ran the whole thing
  again with `tail` bolted on. The middle is dropped instead, and the reply says how much.

### Fixed

- **"Jump to latest" landed in the wrong place, and sometimes did nothing at all.** Two faults on
  top of each other. The index it scrolled to counted tool and system messages, which are not rows
  in the transcript, so it aimed short of the end; and `scrollToItem` aligns an item's *top* with
  the viewport, which for an answer longer than the screen is the first line of the reply rather
  than the bottom of the conversation.
- **The transcript stopped following a reply that was still arriving.** Whether to follow was
  decided by asking "are we at the bottom?", and a streaming answer moves the bottom away from the
  reader on every token — so the test said "the reader has scrolled up" several times a second at a
  reader who had not moved, which also made the button flicker in and out. Following is now given up
  only on a scroll backwards and taken up again only on reaching the end — or on sending a message,
  since nobody types one in order to carry on reading something further up. The tail is followed
  through tool calls too, which change a turn's height without changing a character of its text.

## [1.5.1] - 2026-08-15

### Fixed

- **A shell command that would not finish could hold a turn open past its timeout.** Only for
  pipelines, and only on Android and Linux, which is why it survived 1.5.0: `sh -c` runs a lone
  command by becoming it, so killing the process killed the command, but it forks for a pipeline
  and the surviving half keeps the output pipe open. The read then blocked for as long as the
  command cared to run, with the deadline doing nothing — the case the deadline exists for. Cirrus
  now stops waiting rather than trying to end the read, which cannot be done from outside on Linux
  by any means: not interruption, not killing the process, not closing the stream.

## [1.5.0] - 2026-08-15

### Added

- **Spotify.** Search the catalogue, read your playlists, saved music and the artists you actually
  listen to, see what is playing, control playback, and make or edit playlists — five tools rather
  than the fifteen endpoints behind them, because eight near-identical schemas on every turn is a
  page of context for nothing. Sign-in is OAuth with PKCE, so there is no client secret in the app;
  it uses a client ID you create at developer.spotify.com, and the redirect URI to paste back is
  shown in Settings → Music with a copy button.
- **Media controls that work without Premium.** Spotify's API refuses playback control on free
  accounts, which left "pause the music" — the most obvious thing anyone would ask a phone
  assistant — failing for a lot of people. `media_control` drives Android's own media buttons
  instead: no account, no subscription, no network, and it works for any player, not just Spotify.
  When Spotify refuses a playback command, the error now points at it.
- **Where you are.** `get_location` answers the questions that depend on it — the weather, what is
  nearby, how far to somewhere. Coarse accuracy only, by design rather than as a fallback: every use
  it has is answered by the neighbourhood, and the difference between that and the doorstep is the
  difference between a useful tool and a tracking device. Off by default, foreground only, never
  from a scheduled agent.
- **A settings catalogue the model can read.** A model asked for a tool that was switched off used
  to be told "unknown tool" — and, unable to tell "this app cannot do that" from "not until somebody
  flips a switch", it guessed the first one and told you your app lacked a feature it shipped with.
  Refusals now name the exact switch and where it lives, and `describe_settings` lets the model
  check before promising anything.
- **Spotify in the MCP catalogue**, alongside GitHub, Sentry, Linear and the rest.
- **A shell, for the everyday mechanical jobs.** Cirrus can now run commands on the phone: counting
  and sorting text, checksums and encodings, working with the scratch files it wrote a moment ago.
  What it may run is decided before anything runs, from a list you can read in one sitting — only
  named programs, no absolute paths, no `..`, no `$(…)`, no background jobs, and nothing that runs
  another program on the command's behalf. The working directory is a scratch folder inside Cirrus's
  own cache, and it is the entire reachable world.
- **The clock, the calendar and this phone.** A model has no clock, so left alone it answers "how
  long until Friday?" from the year it was trained in — wrong in the way that looks most convincing.
  It can now ask for the exact date and time in your own timezone, for a month laid out as a grid,
  and for a summary of the device: Android version, CPU, memory, storage, battery, network, and
  which shell programs this particular phone actually has.
- **Apps, if you want it.** Off by default: list what is installed, open something, or open a store
  page for something that is not. It cannot install anything — Android's own installer asks, every
  time — and it says so where the model can read it.
- **Standing rules, not just tool descriptions.** With the shell switched on, every turn carries two
  sentences the model cannot lose track of: stay non-destructive, and clean up before finishing.
  It is told to empty its workspace when a task is done, and Cirrus empties it at every start
  regardless, so nothing survives a session that nobody asked to keep.
- **Openers written by your own model.** The four suggestions on an empty chat, and the ready-made
  agents, are now generated by the model you have configured — told exactly which tools this install
  has, so nothing is offered that would fail on the first tap. The written-in-advance sets remain
  the floor: they are what shows while the request is in flight, and what stays if it fails. There
  is a "Suggest something else" for when none of the four appeal.

### Changed

- **One switch for write actions, covering everything.** "Allow write actions" used to be a GitHub
  setting. It now governs anything that changes something outside Cirrus and cannot be undone from
  inside it — GitHub, Spotify playlists, and MCP tools. A gate per integration meant the third
  integration shipped without one, which is exactly what had happened. If you had allowed GitHub
  writes, that carries over; you will not be asked again.
- **MCP tools are assumed to write unless they say otherwise.** The protocol has optional
  annotations for this and most servers omit them, so Cirrus now treats an unannotated tool as one
  that changes things. Every other tool in the app can be understood by reading its source; an MCP
  tool is a function on somebody else's server, described by that server. **This will withhold tools
  from servers you already have attached** until you allow write actions — which is the point, since
  until now nothing asked at all.
- **Playback control is not a write.** Pausing is undone by playing, so it stays available with the
  write switch off. The test is reversibility, not severity.
- **Memory, notifications and the nightly tidy-up have switches at last.** All three were settings
  with no interface anywhere — you could not turn memory off. They are in Settings → Tools.
- **The empty chat says hello.** A cloud and "Good afternoon", side by side, in place of a large
  mark above a question the four rows underneath were already answering.
- **Bold and italic are told apart.** At reading size on a bright screen, 400 against 700 was a
  difference you had to go looking for, and a slanted run and a heavy run are both merely "darker"
  in peripheral vision. Strong text is heavier and tracks tighter; emphasis is a real italic, opened
  up. Headings are bold at every level — the bottom three are set at or below body size, where
  weight was the only thing distinguishing them.
- **The drawer is the conversation list again.** Agents and Memory moved out of it and back to
  Settings. Three rows of somewhere-else under the list made the thing the drawer is *for* look like
  one option among several.

## [1.4.0] - 2026-08-14

### Added

- **A setup that proves itself.** Cirrus can do nothing until it can reach a model, and neither way
  of arranging that — a key from ollama.com, or a machine on your network running Ollama — is
  guessable from a blank chat screen. A short wizard now asks which one you have, links out to
  create the key, and ends on a request that demonstrably worked rather than on a form that was
  filled in. It finishes by offering an agent to start with. It can be skipped from any step,
  skipping counts as done, and **Settings → Run setup again** reopens it — which also makes it the
  answer to "it stopped working", since the connection test is the same one.
- **Run history for every agent.** The card shows the last run, which cannot tell "it worked this
  morning" apart from "it has failed every morning this week". Every attempt is now recorded with
  its duration, tool calls, token count and whether you started it by hand, and the second case
  looks nothing like the first.
- **Agents start from a worked example.** Six of them — a morning briefing, a topic watch, a
  repository triage, a weekly review, tomorrow's plan, one idea a day — each opening the ordinary
  editor with its fields filled in, to be edited before it is saved. The blank editor is why most
  people never made a second agent, and often not a first.
- **When it next runs, in words.** A card says "Next tomorrow at 07:30" rather than leaving you to
  work it out from "07:30 · weekdays", and shows a live indicator while a run is in flight.
- **Agents and Memory are in the drawer.** Both were two taps deep inside Settings, which reads as
  configuration rather than as somewhere with content in it.

### Changed

- **An agent's answers stay out of your conversations.** A daily agent contributed a thread a day to
  the same list as the conversations you actually had, so after a fortnight the drawer was mostly
  machine and your own threads had been pushed off the end. Runs now live on the agent that wrote
  them. They are still ordinary threads — open, scroll, branch, export — and replying to one moves
  it into your conversation list, because a thread you have joined in on is a conversation rather
  than an artefact. A banner on a run says so, with a **Keep** button for when you want it without
  answering. Threads written before this update are moved across automatically.
- **Agents tidy up after themselves.** Each keeps a set number of runs — ten by default, adjustable
  in the editor — and older threads are deleted after each run. Anything you replied to or kept is
  never touched.
- **Deleting an agent says what it will take with it**, and asks first. It used to delete
  immediately and leave its threads behind, invisible everywhere in the app.
- **Suggested openers match what is switched on.** The three fixed examples on an empty chat have
  been replaced by four drawn from what this install can actually do — no offer to read your
  repositories unless a GitHub token is configured, since a suggestion that fails is worse than no
  suggestion. They can be turned off in **Settings → Chats**.

### Fixed

- **Two agents at the same time swapped notifications.** The tool that puts something on the shade
  carries the conversation its notification should open, and it is a single shared object, so two
  runs overlapping — which "08:00 on weekdays" makes likely — handed each other's threads to each
  other's notifications. Only one agent runs at a time now, which also stops two generations
  competing for the same phone.
- **A stalled run left an agent stuck forever.** A stream that stops delivering does not fail, it
  just goes quiet, so the run blocked until the system killed it at its own deadline — leaving the
  agent marked as running and, worse, skipping the booking that would have scheduled tomorrow. Runs
  now stop themselves first, and anything killed by a reboot or by the phone reclaiming memory is
  closed out at the next start instead of showing a spinner for something that ended days ago.
- **A cancelled run was recorded as a finished one**, so an agent could appear to have answered
  when it had been stopped mid-sentence.
- **One dropped connection no longer costs you the day.** Every failure was final, so a network
  blip at 07:30 meant no briefing. Transport failures are retried twice, half a minute apart; a
  rejected key deliberately is not, since re-running the generation only rediscovers that the key
  is still wrong.
- **An agent switched off while the app was closed ran anyway.** Re-booking on startup never
  cancelled what should no longer fire, so a switch marked off did the thing anyway.
- **Scheduled prompts are no longer remembered as facts about you.** The nightly memory pass read
  agent threads along with everything else, so a daily agent's own instructions were harvested as
  something durable about you every single night.
- **Saving a key and then testing it no longer races itself.** The connection test could run against
  the previous key, reporting a failure for a key that was fine.

## [1.3.1] - 2026-08-13

### Fixed

- **A GitHub tool could run when it was never offered.** The registry decides what to show the model
  and, separately, what may actually run when the model names something anyway — from an earlier
  turn of the same thread, or a guess. The second check asked about the conversation's tools switch
  but not about GitHub's own two gates, so a model naming `github_list_repos` reached GitHub with
  your token attached even with the feature switched off, or with no token configured at all.
  Writes were already refused by the client itself; reads were not.
- **A brief restatement could delete a detailed memory.** Duplicate detection scores overlap as a
  share of the shorter memory's terms, so "prefers Kotlin" always matches "prefers Kotlin over Java
  for Android work" — correctly, they are one memory. But the incoming wording then overwrote the
  stored one in place, silently dropping the qualifier with no way back. A fold now keeps the
  stored wording unless the new one actually adds something.
- **Ripples were square on rounded controls.** Several bordered cards and pill buttons attached
  their click handling outside the surface that clips to the shape, so pressing one painted a
  rectangle across its corners.

## [1.3.0] - 2026-08-13

### Changed

- **Cirrus looks like what it talks to.** The interface has been rebuilt around ollama.com's visual
  language: a monochrome page, hairline rules instead of shadows, a full pill on anything you can
  press, and headings set in a rounded display face over the platform's own text face. The warm
  clay palette and its six competing corner radii are gone; there is one neutral ramp and two radii.
- **Colour now means something.** It appears in exactly three places: the capability tags under a
  model name — cyan for vision, blue for tools, indigo for thinking, as on ollama.com itself — plus
  links and search hits. Everything else, including every control and every selected row, is a step
  on the grey ramp. A tinted panel no longer competes with the syntax highlighting inside it.
- **Cards show their edges.** Reasoning traces, tool calls, model cards, settings groups and the
  composer are bordered rather than filled, which is what lets a dense list read as one object
  instead of a stack of grey slabs.
- **A new launcher icon.** The cloud is now ink on a pale plate rather than pale on a dark one, and
  it has been redrawn wider, flatter and smaller so it is properly centred with room to breathe
  inside the launcher's mask. The notification icon shares its geometry exactly; the two had
  quietly drifted into different shapes.

### Removed

- **Dynamic colour.** Material You repaints the app from your wallpaper, which is directly at odds
  with a design built on having no colour in it — a lilac-tinted monochrome interface is neither.
  Light and dark remain, since that is a question about the room you are in rather than about the
  design.

## [1.2.0] - 2026-08-13

### Added

- **Maths is typeset, not approximated.** Formulas were flattened to Unicode, so `\frac{a}{b}`
  arrived as `a/b`, a summation lost its limits and a matrix was hopeless. There is now a real
  layout engine: fractions stack over a rule, scripts sit where scripts belong, delimiters and
  radicals stretch to fit what is inside them, and matrices, `cases` and aligned environments come
  out as matrices, cases and aligned environments. Spacing follows TeX's rules, so `a + b` is
  looser than `ab` and `a = b` looser still — which is most of what separates maths from a row of
  symbols. Display equations get their own line and scroll sideways when they are wider than the
  screen; long-press copies the LaTeX.
- **Answers can be selected.** Long-press any reply to select and copy part of it. Formulas copy
  as readable text rather than as a gap, so a selected paragraph gives you `x²`, not a hole.
- **Read aloud.** A speak button under finished replies. What gets spoken is not the raw markdown:
  code blocks are announced rather than dictated, links read as "link", tables as heading-and-value
  pairs, and maths as words — "the sum from i equals 1 to n, of x sub i".
- **ElevenLabs voices, optionally.** Bring an API key and replies are read in a far better voice,
  with the voice and model chosen in settings. Without a key it uses Android's own engine; there is
  no state in which the button does nothing.
- **Find in conversation.** Search the open thread from the overflow menu, with every match
  highlighted and arrows to step between them.
- **Memory across conversations.** Cirrus can write down something worth keeping and look it up
  later, through tools it drives itself — deliberately on demand, so what it keeps is a short list
  of durable facts rather than a summary of everything you have ever said. **Settings → Memory**
  shows every line, and lets you edit, pin, retire or delete any of it. Pinned memories are sent
  with every message; the rest have to be recalled.
- **Overnight consolidation.** Once a night, on a charger, Cirrus reads the threads since the last
  pass, writes down anything durable that was said in passing, merges memories that say the same
  thing and retires what has been overtaken. Nothing is deleted — retired memories can be restored.
- **Scheduled agents.** A prompt that runs on its own clock: a morning briefing, a Friday summary,
  a nightly check on something. Each run writes into an ordinary conversation you can open, scroll,
  branch from and reply to, and can notify you when it lands. **Settings → Agents**.
- **A notification tool.** The model can put something on your notification shade when you asked to
  be told about it — and a scheduled agent can reach you with what it found at 3am.

### Changed

- **Settings is a hub rather than a scroll.** Thirty controls in one column, ordered by when each
  was written, became eight groups plus Memory and Agents. Nothing was removed.
- **The launcher icon is just the cloud.**

### Fixed

- **Memory and notifications are no longer behind the tools switch.** That switch governs what
  reaches off the phone — search, GitHub, MCP — and gating local, instant, free tools behind it
  meant memory silently doing nothing in most conversations.
- **The tools switch now actually stops a tool.** Only the schemas were gated, so a model that
  named `web_search` anyway — from an earlier turn, or by guessing — would still have reached the
  network with external tools switched off. Resolving a tool now applies the same gate that
  decides which ones are offered.

## [1.1.0] - 2026-08-11

### Added

- **MCP servers.** Attach a Model Context Protocol server and its tools are offered to the model
  alongside Cirrus's own, under the same per-conversation tools switch. Servers live in
  **Settings → Tools → MCP servers**; each one can be switched off without being removed, and its
  token is stored with the same Keystore-backed encryption as the Ollama and GitHub keys.
- **Discovery before you commit.** Adding a server connects to it, asks what tools it offers, and
  lists them — with the transport it negotiated — before anything is saved. A URL that parses
  proves nothing, and a misconfigured server otherwise fails much later, mid-answer, as a tool
  call the model cannot explain. Editing the URL or token after testing marks the result stale
  rather than showing a number that no longer applies.
- **A short list of known servers** (GitHub, Sentry, Linear, Hugging Face, DeepWiki) that prefill
  the form. They are starting points, not endorsements, and each still has to be reached before
  it can be saved.
- **Jump to latest.** Scrolling up during an answer now offers a way back, labelled "New response"
  while a turn is still streaming.

### Fixed

- **Scrolling up mid-answer no longer fights you.** The transcript followed the tail on every
  token, so trying to re-read something while a response streamed dragged you straight back to
  the bottom. It now follows only when you are already at the bottom — and does so without
  restarting an animation per token, which was also the source of some of the jitter.
- **Your own messages can be acted on.** Copy, edit and resend, branch and delete were all
  implemented and reachable for assistant turns only; the actions sheet had an edit-and-resend
  branch that nothing could open. Long-press any message you sent.
- **Touch targets meet the 48dp minimum.** Nine controls — the composer's icon row, the send
  button, the per-message actions, the code-block buttons, the conversation overflow menu and the
  error banner's dismiss — had hit areas smaller than their icons suggested.
- **TalkBack announces answers.** A completed response is now a polite live region, so it is read
  out when it lands instead of arriving in silence.

### Security

- **An MCP server can no longer receive your GitHub token.** The MCP transports shared the GitHub
  HTTP client, whose interceptor replaces `Authorization` on every request it handles — so a
  server's own token would have been overwritten with the user's GitHub PAT and sent to a
  third-party host. MCP now has its own client that attaches no credential of its own. Nothing
  was exposed in a released build: no code path reached the MCP client until this release.

## [1.0.2] - 2026-08-11

### Fixed

- **Replies no longer die when Cirrus leaves the screen.** A turn ran in the chat screen's own
  scope, so it was cancelled the moment that screen went away — switching threads killed it
  outright, and backgrounding the app left it to be frozen by Android within seconds of the
  process being cached, which stalls the socket until the connection dies. Turns now belong to
  the application, and a foreground service keeps the process awake and unfrozen for as long as
  one is running. Lock the phone mid-answer and the answer is finished when you come back.
- **A cut-off reply is no longer presented as a finished one.** A stream that ended without its
  terminal chunk was treated as a completed answer, which is what made an interrupted reply look
  like the model deciding to stop — reliably at a sentence end, because that is where tokens
  land. It is now reported as the interruption it is, with the partial text kept, and a round
  that dies before producing anything is quietly retried.
- **Spending the tool budget no longer abandons the task.** When a turn used up its tool rounds,
  the model's pending calls were dropped and the turn ended right there — often mid-plan, and
  sometimes with no text at all. Cirrus now asks once more with the tools withheld, so the turn
  ends on an answer that says what was found and what is still outstanding.

### Added

- **A notification while a reply is streaming**, showing that Cirrus is working and offering a
  Stop that works from anywhere. Permission is asked for at your first generation; refusing it
  costs the notification, not the reply.
- **A per-thread error banner that waits for you.** A failure on a thread you are not looking at
  is shown when you return to it, rather than being lost.

## [1.0.1] - 2026-08-10

### Fixed

- **Threads no longer stay called "New chat".** Auto-titling asked for a title in a way that
  quietly produced nothing on the models most people run it against. Ollama enables thinking by
  default on any model that supports it, so a reasoning model spent the whole 24-token title
  budget on its reasoning and returned an empty answer; one that emits raw `<think>` tags inline
  titled the thread `<think>` instead. Thinking is now switched off explicitly where the model
  has the capability (and the field is omitted entirely where it does not), the budget is wide
  enough to survive a model that reasons anyway, and any reasoning left in the reply is stripped
  before the title is taken.
- **Titling no longer dies when you leave the thread.** It ran inside the chat screen's
  generation job, so switching conversations or backing out in the second after an answer landed
  cancelled the request and left the thread unnamed for good. It now runs on the application
  scope, and stopping a generation still names the thread it produced.
- **A thread always ends up named.** If the host is unreachable or the model returns nothing
  usable, the thread takes its name from its opening message instead, and the next turn still
  tries for a real summary.

## [1.0.0] - 2026-08-09

First release. An Android Ollama client for developers who want their local models to actually
do things.

### Added

- **Capability-aware model picker.** Cards carry parameter count, quantization, on-disk size and
  context window, with capability chips read from `/api/show` rather than guessed from the
  model's name. Filter by vision, reasoning, tools, cloud or local.
- **Token streaming** straight from `/api/chat` over NDJSON. Stopping a generation cancels the
  HTTP call, so the server stops generating too.
- **Reasoning traces.** `thinking` deltas stream into a collapsible section, with an effort
  control for models that support one.
- **Tool calling** in bounded multi-round loops: web search, page fetch, and twelve GitHub tools.
- **GitHub integration.** Read code in public and private repositories, search, browse trees,
  read issues and pull request diffs. Opening issues, commenting, posting reviews and committing
  files sit behind a separate switch that is off by default — and with it off, those tools are
  never offered to the model at all.
- **Markdown built to survive streaming**, with a hand-written lexer for syntax highlighting and
  a practical subset of LaTeX maths rendered as Unicode.
- **Voice dictation**, preferring Android's on-device recogniser.
- **Full sampling control** — temperature, top-p, top-k, min-p, penalties, seed, `num_ctx`,
  `num_predict`, stop sequences, JSON schema output, `keep_alive` — each independently
  overridable and each explained where it sits.
- **Conversation management**: fork from any message, edit and resend, regenerate, export to
  Markdown, and threads that name themselves from their content.
- **Secrets encrypted at rest** with an AES-GCM key generated in and never released from the
  Android Keystore. No analytics, no crash reporter, no telemetry.
- **An MCP client** speaking both HTTP transports — the current streamable-HTTP one and the older
  two-channel SSE one, with the transport auto-detected when a server answers the streamable-HTTP
  handshake with an `endpoint` event. Not yet reachable from the UI; see the README.
- **Signed release builds.** Pushing a `vX.Y.Z` tag builds, signs and verifies an APK and
  publishes it to GitHub Releases with generated notes and a `.sha256`, so Obtainium can track
  it. See [docs/RELEASING.md](docs/RELEASING.md).
- **An F-Droid build recipe**, staged in `klaibercore/fdroid-cirrus-metadata`, pending submission
  to fdroiddata now that there is a tag to build from.

### Known gaps

- MCP has no configuration UI. The client is complete and tested, but nothing persists a server
  or bridges its tools into the registry.
- LaTeX is mapped to Unicode, not typeset. There is no layout, so fractions render as `a/b`.

[Unreleased]: https://github.com/klaibercore/cirrus/compare/v2.2.0...HEAD
[2.2.0]: https://github.com/klaibercore/cirrus/compare/v2.1.1...v2.2.0
[2.1.1]: https://github.com/klaibercore/cirrus/compare/v2.1.0...v2.1.1
[2.1.0]: https://github.com/klaibercore/cirrus/compare/v2.0.1...v2.1.0
[2.0.1]: https://github.com/klaibercore/cirrus/compare/v2.0.0...v2.0.1
[2.0.0]: https://github.com/klaibercore/cirrus/compare/v1.9.0...v2.0.0
[1.9.0]: https://github.com/klaibercore/cirrus/compare/v1.8.0...v1.9.0
[1.8.0]: https://github.com/klaibercore/cirrus/compare/v1.7.0...v1.8.0
[1.7.0]: https://github.com/klaibercore/cirrus/compare/v1.6.0...v1.7.0
[1.6.0]: https://github.com/klaibercore/cirrus/compare/v1.5.1...v1.6.0
[1.5.1]: https://github.com/klaibercore/cirrus/compare/v1.5.0...v1.5.1
[1.5.0]: https://github.com/klaibercore/cirrus/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/klaibercore/cirrus/compare/v1.3.1...v1.4.0
[1.3.1]: https://github.com/klaibercore/cirrus/compare/v1.3.0...v1.3.1
[1.3.0]: https://github.com/klaibercore/cirrus/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/klaibercore/cirrus/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/klaibercore/cirrus/compare/v1.0.2...v1.1.0
[1.0.2]: https://github.com/klaibercore/cirrus/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/klaibercore/cirrus/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/klaibercore/cirrus/releases/tag/v1.0.0
