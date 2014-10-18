package org.ethereum.net.eth;

import static org.ethereum.net.eth.EthMessageCodes.TRANSACTIONS;

import org.ethereum.core.Transaction;
import org.ethereum.net.eth.EthMessage;
import org.ethereum.util.RLP;
import org.ethereum.util.RLPList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Wrapper around an Ethereum Transactions message on the network 
 * 
 * @see {@link org.ethereum.net.eth.EthMessageCodes#TRANSACTIONS}
 */
public class TransactionsMessage extends EthMessage {
	
    private Set<Transaction> transactions;

    public TransactionsMessage(byte[] encoded) {
        super(encoded);
    }

    public TransactionsMessage(Set<Transaction> transactionList) {
        this.transactions = transactionList;
        parsed = true;
    }
    
    private void parse() {
		RLPList paramsList = (RLPList) RLP.decode2(encoded).get(0);

		transactions = new HashSet<>();
        for (int i = 1; i < paramsList.size(); ++i) {
            RLPList rlpTxData = (RLPList) paramsList.get(i);
            Transaction tx = new Transaction(rlpTxData.getRLPData());
            transactions.add(tx);
        }
        parsed = true;
    }
    
    private void encode() {
    	List<byte[]> encodedElements = new ArrayList<>();
    	encodedElements.add(RLP.encodeByte(TRANSACTIONS.asByte()));
    	for (Transaction tx : transactions)
            encodedElements.add(tx.getEncoded());
		byte[][] encodedElementArray = encodedElements
				.toArray(new byte[encodedElements.size()][]);
        this.encoded = RLP.encodeList(encodedElementArray);
    }
    
    @Override
    public byte[] getEncoded() {
    	if (encoded == null) encode();
    	return encoded;
    }


    public Set<Transaction> getTransactions() {
        if (!parsed) parse();
        return transactions;
    }

    @Override
    public EthMessageCodes getCommand(){
        return EthMessageCodes.TRANSACTIONS;
    }

    @Override
    public Class<?> getAnswerMessage() {
        return null;
    }
    
    public String toString() {
        if(!parsed) parse();
        StringBuffer sb = new StringBuffer();
        for (Transaction transaction : transactions)
            sb.append("\n   ").append(transaction);
        return "[" + this.getCommand().name() + sb.toString() + "]";
    }
}