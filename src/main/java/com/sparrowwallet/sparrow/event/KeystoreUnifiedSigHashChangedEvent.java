package com.sparrowwallet.sparrow.event;

import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.Wallet;

import java.util.List;

/**
 * Posted when the mark saying a device signs the unified opt-in signature hash has changed on one or more keystores,
 * and the wallet is saved.
 *
 * Its own event rather than part of the settings save, because it changes no address the wallet derives: the wallet
 * is not rewritten for it, so the change reaches a database backed wallet only through a targeted update, as a
 * keystore label does.
 */
public class KeystoreUnifiedSigHashChangedEvent extends WalletSettingsChangedEvent {
    private final List<Keystore> changedKeystores;

    public KeystoreUnifiedSigHashChangedEvent(Wallet wallet, Wallet pastWallet, String walletId, List<Keystore> changedKeystores) {
        super(wallet, pastWallet, walletId);
        this.changedKeystores = changedKeystores;
    }

    public List<Keystore> getChangedKeystores() {
        return changedKeystores;
    }
}
