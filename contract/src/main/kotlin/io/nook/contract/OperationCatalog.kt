package io.nook.contract

/**
 * Everything an adapter can ask the core to do: the seven mutations of the
 * write path and the four reads, under one interface.
 *
 * Two things implement it and nothing else may. Inside the core, one holds the
 * write and read services and delegates. Outside it, the calling library sends
 * each call across the connection and reads the reply — so an adapter written
 * against this interface reaches the same eleven operations either way, and the
 * same call reaches the same verdict.
 *
 * A [projectRef] is the project an operation acts *inside*, and only the seven
 * project-scoped operations take one. The other four act on the instance — one
 * running Nook and every project in it — so they name no project to work
 * inside, even where they name one to work *on*: `getProject` addresses a
 * project the way `getItem` addresses an item, and the project it addresses is
 * its target rather than its scope.
 *
 * References work as they do everywhere else: a string in UUID form resolves as
 * an id, anything else as a handle, and an item or release resolves inside its
 * bound project alone. A refused call throws [StructuredErrorException]; the
 * two deletes return nothing, because what they removed no longer exists.
 */
public interface OperationCatalog {

    // ── on the instance ──────────────────────────────────────────────────────

    public fun createProject(command: CreateProject): Project

    public fun getProject(ref: String): Project

    public fun listProjects(): List<Project>

    public fun deleteProject(ref: String)

    // ── inside one project ───────────────────────────────────────────────────

    public fun createItem(projectRef: String, command: CreateItem): ProjectItem

    public fun updateItem(projectRef: String, itemRef: String, command: UpdateItem): ProjectItem

    public fun deleteItem(projectRef: String, itemRef: String)

    public fun createRelease(projectRef: String, command: CreateRelease): Release

    public fun updateRelease(projectRef: String, releaseRef: String, command: UpdateRelease): Release

    public fun getItem(projectRef: String, itemRef: String): ProjectItem

    public fun listItems(projectRef: String, filter: ItemFilter): List<ProjectItem>
}

/**
 * The eleven operations under the names they travel by, which are the names the
 * design documents use for them and the names a caller writes in a request.
 *
 * An operation arrives as text and is looked up here, rather than being decoded
 * into this type directly: a name nobody defined has to come back as a refusal
 * naming it, and text that failed to decode has nothing left to name.
 */
public enum class CatalogOperation(public val label: String) {
    CREATE_PROJECT("create_project"),
    GET_PROJECT("get_project"),
    LIST_PROJECTS("list_projects"),
    DELETE_PROJECT("delete_project"),
    CREATE_ITEM("create_item"),
    UPDATE_ITEM("update_item"),
    DELETE_ITEM("delete_item"),
    CREATE_RELEASE("create_release"),
    UPDATE_RELEASE("update_release"),
    GET_ITEM("get_item"),
    LIST_ITEMS("list_items"),
    ;

    public companion object {
        public fun fromLabel(label: String): CatalogOperation? = entries.firstOrNull { it.label == label }
    }
}
