# DJMix architecture (for reading the code)

Two halves. The **console** (decks, mixer, waveforms, knobs) is classic Android Views driven by `MainActivity`; it is
timing-sensitive and custom-drawn, so it stays as it is. Everything **around** it (the Load screen, sign-in, the cloud)
follows the modern pattern: **Repository → ViewModel → Compose screen**, wired together by **Hilt**.

```
                 Firebase Auth        Firebase Storage           phone files (library/, meta/, cache/)
                      │                      │                              │
   data layer   AuthRepository ──► CloudRepository ◄── Prep (background queue) ── Library / TrackMeta (objects)
                 (who is in)        (shared cloud)      (download, decode,          (plain file helpers,
                      │                  │               analyse, fetch)             no state, no DI needed)
                      └────────┬─────────┴──────────────┬──────┘
   state layer         AccountViewModel           LoadViewModel           ← @HiltViewModel, expose StateFlow<UiState>
                              │                        │
   UI layer            AccountPanel.kt            LoadScreen.kt            ← @Composable, draw state, send taps back
                              └──────── LoadScreenFragment ────────┘      ← hosts Compose over the Views console
                                              │
                                         MainActivity (LoadHost: the decks)
```

## 1. Dependency injection (Hilt)

Instead of every class reaching for global singletons (`Cloud.upload(...)`, `Prep.enqueue(...)`), a class *declares*
what it needs in its constructor and Hilt builds and hands it over.

- `DJMixApp` (`@HiltAndroidApp`) creates the app-wide container.
- `di/FirebaseModule` tells Hilt how to make what it cannot construct (`FirebaseAuth`, `FirebaseStorage`).
- Our own classes just say `@Singleton class Prep @Inject constructor(app: Context, cloud: CloudRepository)`. Hilt sees
  Prep needs CloudRepository, CloudRepository needs AuthRepository + FirebaseStorage, and builds them in that order,
  once each.
- `MainActivity` and `LoadScreenFragment` are `@AndroidEntryPoint`: fields marked `@Inject lateinit var` get filled.
- ViewModels are `@HiltViewModel`; a screen gets one with `hiltViewModel()`.

Why it matters: dependencies are visible in one line (the constructor), there is exactly one Prep and one cloud for
the whole app, and a test can pass a fake instead of the real thing.

Not everything needs DI: `Library`, `TrackMeta`, `Stems`, the audio engine are stateless helpers or self-contained;
they stay `object`s.

## 2. State flows one way (StateFlow → ViewModel → Compose)

- Repositories hold the truth as `StateFlow`s: `AuthRepository.user`, `CloudRepository.tracks / wanted / refreshing /
  uploading`, `Prep.jobs`. A StateFlow always has a current value and tells collectors when it changes.
- `LoadViewModel.state` `combine`s those with the screen's own filters (tab, search, playlist, sort) into one
  immutable `UiState`. Every change anywhere produces a new `UiState`.
- `LoadScreen` does `val s by vm.state.collectAsStateWithLifecycle()` and draws `s`. Compose redraws only what changed;
  `LazyColumn` with `key = vid` means a download tick redraws one row, not the list.
- Taps go back as function calls (`vm.fetch(track)`, `vm.setSort(...)`), never by editing state in the screen.
- One-off events (a toast after a failed delete) use a `Channel` (`LoadViewModel.messages`), not state.

## 3. Sign-in and the cloud rule

- Signed out: `CloudRepository.backupOn` is false and every cloud call returns early, so **nothing leaves the phone**.
  The Cloud tab shows `SignInCard`.
- Sign-in is two steps (`AccountViewModel.signIn`): `auth/GoogleSignIn.kt` asks Credential Manager for a Google ID
  token (needs the Activity, so the screen supplies it), then `AuthRepository.signIn` trades it for a Firebase user.
- Signed in: one **shared library** for everyone (`tracks/`, `mixes/`, `wanted/`). With *Auto backup* on (Account
  dialog), new songs, stems and mixes upload by themselves; UPLOAD ALL sends older ones; FETCH ALL copies the rest down.
- Every upload carries `metadata.owner = uid`. `storage.rules` (deploy with `firebase deploy --only storage`): signed-in
  users read everything and add files; only the owner or the admin uid may replace or delete one.

## 4. Background work

`Prep` keeps one low-priority worker thread for CPU work (decode, analyse, LALAL stems) so it never fights the audio
thread, plus a 3-wide coroutine lane for cloud copies (network-bound). A finished copy becomes a PREPARE job on the
worker. Its public face is `jobs: StateFlow<List<Job>>` of immutable `Job` snapshots.

## Read in this order

1. `DJMixApp.kt`, `di/FirebaseModule.kt` (the container)
2. `auth/AuthRepository.kt`, `auth/GoogleSignIn.kt`
3. `library/CloudRepository.kt`, `library/Prep.kt`
4. `ui/load/LoadViewModel.kt` (how state is built), then `ui/load/LoadScreen.kt` (how it is drawn)
5. `ui/account/AccountViewModel.kt`, `ui/account/AccountPanel.kt`
6. `ui/load/LoadScreenFragment.kt` and the `LoadHost` part of `MainActivity.kt` (the bridge to the Views console)
