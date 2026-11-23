import PhotosUI
import SwiftUI

@main
struct ReelCutApp: App {
    @StateObject private var model = AppModel()
    @StateObject private var editor = EditorModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .environmentObject(editor)
                .onAppear {
                    // "-open_project <id>" opens a saved timeline project directly (screenshot test)
                    let args = ProcessInfo.processInfo.arguments
                    if let i = args.firstIndex(of: "-open_project"), i + 1 < args.count { model.openProject(args[i + 1]) }
                }
        }
    }
}

struct RootView: View {
    @EnvironmentObject var vm: AppModel
    @EnvironmentObject var evm: EditorModel

    var body: some View {
        ZStack(alignment: .bottom) {
            RC.background.ignoresSafeArea()
            Group {
                switch vm.screen {
                case .home: HomeView()
                case .working(let st, let p): WorkingView(stage: st, progress: p)
                case .setup(let name): SetupView(songName: name)
                case .result(let r): ResultView(r: r)
                case .viewer(let item): ViewerView(item: item)
                case .fineTune(let ft): FineTuneView(ft: ft)
                case .editor(let id):
                    EditorView(onBack: leaveEditor).task(id: id) { evm.open(id) }
                }
            }
            if let t = vm.toast ?? evm.toast {
                Text(t)
                    .font(.system(size: 14))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16).padding(.vertical, 12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(white: 0.18), in: RoundedRectangle(cornerRadius: 10))
                    .padding(.horizontal, 16).padding(.bottom, 12)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .onTapGesture { vm.toast = nil; evm.toast = nil }
            }
        }
        .animation(.easeOut(duration: 0.2), value: vm.toast ?? evm.toast)
        .tint(RC.primary)
    }

    private func leaveEditor() {
        evm.close { vm.refreshLists() }
        vm.back()
    }
}

// ---------------------------------------------------------------- Home ---

struct HomeView: View {
    @EnvironmentObject var vm: AppModel
    @State private var pickItems: [PhotosPickerItem] = []
    @State private var projectItems: [PhotosPickerItem] = []
    @State private var showMedia = false
    @State private var showProject = false
    @State private var showSong = false
    @State private var showAbout = false
    @State private var menu: Project?
    @State private var newName = ""
    @State private var confirmDelete = false

    /// Projects and exported reels together, newest first.
    private var entries: [(String, Any)] {
        let p = vm.projects.map { ($0.updated, "p:" + $0.id, $0 as Any) }
        let r = vm.recent.map { item -> (Int64, String, Any) in
            let d = (try? item.file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            return (Int64(d.timeIntervalSince1970 * 1000), "r:" + item.id, item as Any)
        }
        return (p + r).sorted { $0.0 > $1.0 }.map { ($0.1, $0.2) }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Text("ReelCut").font(.system(size: 22, weight: .semibold))
                    Spacer()
                    Button { showAbout = true } label: { Image(systemName: "gearshape").font(.system(size: 20)).foregroundStyle(RC.onSurfaceVariant) }
                        .accessibilityLabel("Settings")
                }

                CardBox(spacing: 10) {
                    Label("Auto reel", systemImage: "wand.and.stars").font(.system(size: 16, weight: .semibold))
                        .labelStyle(TintedIcon())
                    Text("Pick clips and a song. The beat decides the edit.").font(.system(size: 13)).foregroundStyle(RC.onSurfaceVariant)
                    FlowRow(spacing: 8) {
                        ForEach(reelLengths, id: \.self) { s in
                            Chip(label: lengthLabel(s), selected: vm.reelLen == s) { vm.setLen(s) }
                        }
                    }
                    BigButton(label: "Pick clips and song") { showMedia = true }
                }

                Button { showProject = true } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "rectangle.split.1x2").font(.system(size: 20))
                        VStack(alignment: .leading, spacing: 2) {
                            Text("New project").font(.system(size: 16, weight: .semibold))
                            Text("Edit by hand on a timeline").font(.system(size: 13)).foregroundStyle(RC.onSurfaceVariant)
                        }
                        Spacer()
                    }
                    .padding(14)
                    .background(RC.surfaceVariant, in: RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)

                Text("Recent projects").font(.system(size: 13)).foregroundStyle(RC.onSurfaceVariant)
                if vm.projects.isEmpty && vm.recent.isEmpty {
                    Text("Your projects and exported reels show here.").font(.system(size: 13)).foregroundStyle(RC.onSurfaceVariant)
                } else {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                        ForEach(entries, id: \.0) { e in
                            if let p = e.1 as? Project {
                                ProjectTile(p: p)
                                    .onTapGesture { vm.openProject(p.id) }
                                    .onLongPressGesture { newName = p.name; confirmDelete = false; menu = p }
                            } else if let it = e.1 as? Library.Item {
                                RecentTile(item: it).onTapGesture { vm.open(it) }
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
            .foregroundStyle(RC.onBackground)
        }
        .photosPicker(isPresented: $showMedia, selection: $pickItems, maxSelectionCount: maxPick,
                      selectionBehavior: .ordered, matching: .any(of: [.images, .videos]), photoLibrary: .shared())
        .photosPicker(isPresented: $showProject, selection: $projectItems, maxSelectionCount: maxPick,
                      selectionBehavior: .ordered, matching: .any(of: [.images, .videos]), photoLibrary: .shared())
        .onChange(of: pickItems) { items in
            guard !items.isEmpty else { return }
            pickItems = []
            Task {
                Picking.clearInbox()
                let urls = await Picking.load(items) { i, n in Task { @MainActor in vm.loading = "Loading clips \(i + 1) of \(n)" } }
                vm.loading = nil
                if urls.count >= 2 { vm.setMedia(urls); showSong = true }
                else { vm.say("Pick at least 2 clips or photos") }
            }
        }
        .onChange(of: projectItems) { items in
            guard !items.isEmpty else { return }
            projectItems = []
            Task {
                let urls = await Picking.load(items) { i, n in Task { @MainActor in vm.loading = "Loading clips \(i + 1) of \(n)" } }
                vm.loading = nil
                if urls.isEmpty { vm.say("These files could not be read") } else { vm.newProject(urls) }
            }
        }
        .fileImporter(isPresented: $showSong, allowedContentTypes: [.audio]) { res in
            switch res {
            case .success(let u):
                if let s = Picking.keepSong(u) { vm.songPicked(s) } else { vm.say("This song could not be opened") }
            case .failure: vm.say("Pick a song to continue")
            }
        }
        .overlay {
            if let l = vm.loading {
                VStack(spacing: 12) { ProgressView(); Text(l).font(.system(size: 14)) }
                    .padding(24).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
            }
        }
        .alert("ReelCut", isPresented: $showAbout) {
            Button("Close", role: .cancel) {}
        } message: {
            Text("Version \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0") (\(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"))\nInternal app. Reels are made on this phone and saved to Photos.")
        }
        .sheet(item: $menu) { p in
            ProjectMenu(p: p, name: $newName, confirmDelete: $confirmDelete) { menu = nil }
                .presentationDetents([.height(260)])
        }
    }
}

struct TintedIcon: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 8) { configuration.icon.foregroundStyle(RC.primary); configuration.title }
    }
}

/// Rename / delete a project (long press on its tile).
struct ProjectMenu: View {
    @EnvironmentObject var vm: AppModel
    let p: Project
    @Binding var name: String
    @Binding var confirmDelete: Bool
    let close: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(confirmDelete ? "Delete this project?" : "Project").font(.system(size: 18, weight: .semibold))
            if confirmDelete {
                Text("\(p.name) will be removed from ReelCut. Your clips and exported videos stay in Photos.").font(.system(size: 14))
                HStack {
                    Button("Cancel") { confirmDelete = false }
                    Spacer()
                    Button("Delete", role: .destructive) { vm.deleteProject(p); close() }
                }
            } else {
                TextField("Name", text: $name).textFieldStyle(.roundedBorder)
                    .onChange(of: name) { v in if v.count > 40 { name = String(v.prefix(40)) } }
                HStack {
                    Button("Delete project", role: .destructive) { confirmDelete = true }
                    Spacer()
                    Button("Save name") { vm.renameProject(p, name); close() }.fontWeight(.semibold)
                }
            }
        }
        .padding(20)
    }
}

/// Lays chips in rows that wrap.
struct FlowRow: Layout {
    var spacing: CGFloat = 8
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let w = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowH: CGFloat = 0, maxX: CGFloat = 0
        for s in subviews {
            let sz = s.sizeThatFits(.unspecified)
            if x > 0 && x + sz.width > w { x = 0; y += rowH + spacing; rowH = 0 }
            x += sz.width + spacing; rowH = max(rowH, sz.height); maxX = max(maxX, x - spacing)
        }
        return CGSize(width: maxX, height: y + rowH)
    }
    func placeSubviews(in b: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = b.minX, y = b.minY, rowH: CGFloat = 0
        for s in subviews {
            let sz = s.sizeThatFits(.unspecified)
            if x > b.minX && x + sz.width > b.maxX { x = b.minX; y += rowH + spacing; rowH = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(sz))
            x += sz.width + spacing; rowH = max(rowH, sz.height)
        }
    }
}

struct ProjectTile: View {
    let p: Project
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            RC.tile
            if let img = UIImage(contentsOfFile: ProjectStore.cover(p.id).path) {
                Image(uiImage: img).resizable().scaledToFill()
            }
            Text(p.name).font(.system(size: 11)).foregroundStyle(.white).lineLimit(2)
                .padding(6).frame(maxWidth: .infinity, alignment: .leading).background(Color.black.opacity(0.6))
        }
        .overlay(alignment: .topTrailing) {
            Image(systemName: "pencil").font(.system(size: 12, weight: .semibold)).foregroundStyle(.white)
                .frame(width: 22, height: 22).background(Color.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 6)).padding(6)
        }
        .aspectRatio(9.0 / 16.0, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .contentShape(Rectangle())
    }
}

struct RecentTile: View {
    let item: Library.Item
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Color(white: 0.07)
            if let img = UIImage(contentsOfFile: item.thumb.path) { Image(uiImage: img).resizable().scaledToFill() }
            Text(item.label).font(.system(size: 11)).foregroundStyle(.white).lineLimit(2)
                .padding(6).frame(maxWidth: .infinity, alignment: .leading).background(Color.black.opacity(0.6))
        }
        .aspectRatio(9.0 / 16.0, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .contentShape(Rectangle())
    }
}

// ------------------------------------------------------------- Working ---

struct WorkingView: View {
    @EnvironmentObject var vm: AppModel
    let stage: String
    let progress: Double

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            Text(stage).font(.system(size: 17, weight: .semibold))
            ProgressView(value: progress).progressViewStyle(.linear).padding(.top, 16)
            Text("\(Int((progress * 100).rounded()))%").font(.system(size: 13)).foregroundStyle(RC.onSurfaceVariant).padding(.top, 8)
            Text("Keep the app open").font(.system(size: 13)).foregroundStyle(RC.onSurfaceVariant).padding(.top, 6)
            Button("Cancel") { vm.back() }
                .padding(.horizontal, 22).padding(.vertical, 9)
                .overlay(Capsule().stroke(RC.outline)).padding(.top, 24)
            Spacer()
        }
        .padding(32)
        .foregroundStyle(RC.onBackground)
    }
}

// --------------------------------------------------------------- Setup ---

/// After picking clips + song: music, shape, cut speed, optional title, then Make reel.
struct SetupView: View {
    @EnvironmentObject var vm: AppModel
    let songName: String

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                TopBar(title: "Auto reel") { vm.back() }
                CardBox {
                    Text("Music").font(.system(size: 15, weight: .semibold))
                    HStack { Chip(label: "My song", selected: true) {} }
                    Text(songName).font(.system(size: 13)).foregroundStyle(RC.onSurfaceVariant).lineLimit(1)
                }
                CardBox {
                    Text("Shape").font(.system(size: 15, weight: .semibold))
                    FlowRow(spacing: 8) {
                        ForEach(Shape.allCases, id: \.self) { sh in Chip(label: sh.label, selected: vm.options.shape == sh) { vm.setShape(sh) } }
                    }
                }
                CardBox {
                    Text("Cut speed").font(.system(size: 15, weight: .semibold))
                    HStack(spacing: 6) {
                        ForEach(Pace.allCases, id: \.self) { p in Pill(label: p.label, selected: vm.options.pace == p) { vm.setPace(p) } }
                    }
                    Text(paceHint).font(.system(size: 13)).foregroundStyle(RC.onSurfaceVariant)
                }
                CardBox {
                    Text("Title").font(.system(size: 15, weight: .semibold))
                    TextField("Text on the first shot (optional)", text: Binding(get: { vm.options.title }, set: { vm.setTitle($0) }))
                        .textFieldStyle(.roundedBorder)
                }
                BigButton(label: "Make reel") { vm.start() }
            }
            .padding(.horizontal, 16).padding(.vertical, 8)
            .foregroundStyle(RC.onBackground)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    private var paceHint: String {
        switch vm.options.pace {
        case .auto: return "Auto: soft songs get slow cuts and dissolves"
        case .smooth: return "A new shot about every 3-4 s, soft dissolves"
        case .normal: return "A new shot about every 1.5-2.5 s"
        case .fast: return "Cuts on the beat"
        }
    }
}

// -------------------------------------------------------------- Result ---

struct ResultView: View {
    @EnvironmentObject var vm: AppModel
    let r: ResultState
    @State private var sharing: URL?

    var body: some View {
        VStack(spacing: 10) {
            TopBar(title: [r.lookName, "\(r.bpm) BPM", r.pace].filter { !$0.isEmpty }.joined(separator: " · ")) { vm.back() }
            LoopingPlayer(url: r.exported ?? r.file)
                .clipShape(RoundedRectangle(cornerRadius: 12))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(Looks.all, id: \.key) { l in
                        Chip(label: l.name, selected: r.lookKey == l.key, disabled: r.exporting) { vm.pickLook(l.key) }
                    }
                }
                .padding(.vertical, 2)
            }
            HStack(spacing: 8) {
                BigButton(label: "Another edit", icon: "arrow.clockwise", outlined: true, disabled: r.exporting) { vm.anotherEdit() }
                BigButton(label: "Fine-tune", icon: "slider.horizontal.3", outlined: true, disabled: r.exporting) { vm.openFineTune() }
            }
            if r.exporting {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Exporting 1080p… \(Int((r.exportProgress * 100).rounded()))%").font(.system(size: 13))
                    ProgressView(value: r.exportProgress)
                }
                .frame(height: 50)
            } else if let e = r.exported {
                BigButton(label: "Share", icon: "square.and.arrow.up") { sharing = e }
            } else {
                BigButton(label: "Export 1080p") { vm.export() }
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .foregroundStyle(RC.onBackground)
        .sheet(item: Binding(get: { sharing.map { ShareItem(url: $0) } }, set: { sharing = $0?.url })) { s in
            ShareSheet(items: [s.url])
        }
    }
}

struct ShareItem: Identifiable { let url: URL; var id: String { url.path } }

// -------------------------------------------------------------- Viewer ---

struct ViewerView: View {
    @EnvironmentObject var vm: AppModel
    let item: Library.Item
    @State private var sharing = false

    var body: some View {
        VStack(spacing: 10) {
            TopBar(title: item.label, back: { vm.back() }) {
                Button { vm.delete(item) } label: { Image(systemName: "trash").frame(width: 40, height: 40) }
                    .accessibilityLabel("Delete from app")
            }
            LoopingPlayer(url: item.file).clipShape(RoundedRectangle(cornerRadius: 12))
            BigButton(label: "Share", icon: "square.and.arrow.up") { sharing = true }
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .foregroundStyle(RC.onBackground)
        .sheet(isPresented: $sharing) { ShareSheet(items: [item.file]) }
    }
}
