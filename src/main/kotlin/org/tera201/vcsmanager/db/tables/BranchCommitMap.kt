package org.tera201.vcsmanager.db.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object BranchCommitMap: Table("branch_commit_map") {
    val projectId = reference("projectId", Projects.id, ReferenceOption.CASCADE, ReferenceOption.CASCADE)
    val branchId = reference("branchId", Branches.id, ReferenceOption.CASCADE, ReferenceOption.CASCADE)
    val commitHash = reference("commitHash", Commits.hash, ReferenceOption.CASCADE, ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(branchId, commitHash)
}