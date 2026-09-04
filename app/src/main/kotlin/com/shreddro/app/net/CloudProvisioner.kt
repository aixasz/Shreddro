package com.shreddro.app.net

/**
 * Creates the cloud layout up front — right after an account is linked and
 * on every launch while linked — instead of lazily on the first slip:
 *
 *     Shreddro/                            (root folder)
 *     Shreddro/Shreddro Transactions[.xlsx] (central ledger, header row only)
 *     Shreddro/<bank>/                     (one per bank already in the local ledger)
 *
 * Everything is find-or-create, so calling this repeatedly is free of
 * duplicates; links discovered along the way are reported via the gateways'
 * URL callbacks so the Account tab can show them immediately.
 */
interface CloudProvisioner {
    suspend fun provision(bankKeys: Collection<String>)
}
