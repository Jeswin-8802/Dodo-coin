package io.mycrypto.core.repository;

public enum DbName {
    BLOCKCHAIN,         // Block-Hash ==> block path
    TRANSACTIONS,       // Transaction-Hash ==> Transaction Data (as JSON)
    TRANSACTIONS_POOL,  // Transaction-Hash ==> Transaction Data (as JSON)
    NODES,              // Wallet Address ==> ("IP Address" if foreign | "Wallet Name" if owned)
    WALLETS,            // Wallet-Name ==> "PubKey PrvKey hash-160 dodo-coin-address"
    ACCOUNTS,           // (as JSON) 👇
    /* Wallet Address ==>
    {
        TransactionId1: "VOUT_1,VOUT_2",
        TransactionId2: "VOUT_1",
        .
        .
        .
    }
     */
    WEBRTC,              // used to store information received from peer or server
        // KEYS used for Webrtc DB
        PEERS,          // dodo-address-1, dodo-address-2 ... dodo-address-n
        ICE,            /*
            {
                "dodo-address-1": { ICE Candidate }
            },
            {
                "dodo-address-2": { ICE Candidate }
            },
            .
            .
            .
        */

    P2P                 // dodo-address => [CONNECTED, DISCONNECTED, OFFLINE]
}
