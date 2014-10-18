package org.ethereum.net.eth;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.ethereum.core.Block;
import org.ethereum.core.Transaction;
import org.ethereum.facade.Blockchain;
import org.ethereum.manager.WorldManager;
import org.ethereum.net.BlockQueue;
import org.ethereum.net.MessageQueue;
import org.ethereum.net.PeerListener;
import org.ethereum.net.message.ReasonCode;
import org.ethereum.net.p2p.DisconnectMessage;
import org.ethereum.util.ByteUtil;
import org.ethereum.util.FastByteComparisons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.*;

import static org.ethereum.config.SystemProperties.CONFIG;
import static org.ethereum.net.message.StaticMessages.GET_TRANSACTIONS_MESSAGE;

/**
 * Process the messages between peers with 'eth' capability on the network.
 * <p/>
 * Peers with 'eth' capability can send/receive:
 * <ul>
 * <li>STATUS				:	Announce their status to the peer</li>
 * <li>GET_TRANSACTIONS   	: 	Request a list of pending transactions</li>
 * <li>TRANSACTIONS		    :	Send a list of pending transactions</li>
 * <li>GET_BLOCK_HASHES	    : 	Request a list of known block hashes</li>
 * <li>BLOCK_HASHES		    :	Send a list of known block hashes</li>
 * <li>GET_BLOCKS			: 	Request a list of blocks</li>
 * <li>BLOCKS				:	Send a list of blocks</li>
 * </ul>
 */
public class EthHandler extends SimpleChannelInboundHandler<EthMessage> {

    public final static byte VERSION = 0x23;
    public final static byte NETWORK_ID = 0x0;

    private final static Logger logger = LoggerFactory.getLogger("net");

    private String peerId;
    private PeerListener peerListener;

    private static String hashRetrievalLock;
    private MessageQueue msgQueue = null;

    private SyncSatus syncStatus = SyncSatus.INIT;

    private Timer getBlocksTimer = new Timer("GetBlocksTimer");

    //
    private Timer getTxTimer = new Timer("GetTransactionsTimer");

    public EthHandler(MessageQueue msgQueue) {
        this.msgQueue = msgQueue;
    }

    public EthHandler(String peerId, PeerListener peerListener, MessageQueue msgQueue) {
        this(msgQueue);
        this.peerId = peerId;
        this.peerListener = peerListener;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        logger.info("ETH protocol activated");
        sendStatus();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
//		sendStatus();
    }

    @Override
    public void channelRead0(final ChannelHandlerContext ctx, EthMessage msg) throws InterruptedException {

        if (EthMessageCodes.inRange(msg.getCommand().asByte()))
            logger.info("EthHandler invoke: [{}]", msg.getCommand());

        switch (msg.getCommand()) {
            case STATUS:
                msgQueue.receivedMessage(msg);
				processStatus((StatusMessage) msg, ctx);
                break;
            case GET_TRANSACTIONS:
                msgQueue.receivedMessage(msg);
                sendPendingTransactions();
                break;
            case TRANSACTIONS:
                msgQueue.receivedMessage(msg);
                // List<Transaction> txList = transactionsMessage.getTransactions();
                // for(Transaction tx : txList)
                // WorldManager.getInstance().getBlockchain().applyTransaction(null,
                // tx);
                // WorldManager.getInstance().getWallet().addTransaction(tx);
                break;
            case GET_BLOCK_HASHES:
                msgQueue.receivedMessage(msg);
//				sendBlockHashes();
                break;
            case BLOCK_HASHES:
                msgQueue.receivedMessage(msg);
                processBlockHashes((BlockHashesMessage) msg);
                break;
            case GET_BLOCKS:
                msgQueue.receivedMessage(msg);
//				sendBlocks();
                break;
            case BLOCKS:
                msgQueue.receivedMessage(msg);
                processBlocks((BlocksMessage) msg);
                break;
            case NEW_BLOCK:
                msgQueue.receivedMessage(msg);
                procesNewBlock((NewBlockMessage)msg);
            default:
                break;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error(cause.getCause().toString());
        super.exceptionCaught(ctx, cause);
        ctx.close();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        logger.debug("handlerRemoved: kill timers in EthHandler");
        this.killTimers();
    }

    /**
     * Processing:
     * <ul>
     *   <li>checking if peer is using the same genesis, protocol and network</li>
     *   <li>seeing if total difficulty is higher than total difficulty from all other peers</li>
     * 	 <li>send GET_BLOCK_HASHES to this peer based on bestHash</li>
     * </ul>
     *
     * @param msg is the StatusMessage
     * @param ctx the ChannelHandlerContext
     */
    public void processStatus(StatusMessage msg, ChannelHandlerContext ctx) {

        Blockchain blockchain = WorldManager.getInstance().getBlockchain();

        if (!Arrays.equals(msg.getGenesisHash(), Blockchain.GENESIS_HASH)
                || msg.getProtocolVersion() != EthHandler.VERSION) {
            logger.info("Removing EthHandler for {} due to protocol incompatibility", ctx.channel().remoteAddress());
//			msgQueue.sendMessage(new DisconnectMessage(ReasonCode.INCOMPATIBLE_NETWORK));
            ctx.pipeline().remove(this); // Peer is not compatible for the 'eth' sub-protocol
        } else if (msg.getNetworkId() != EthHandler.NETWORK_ID)
            msgQueue.sendMessage(new DisconnectMessage(ReasonCode.INCOMPATIBLE_NETWORK));
        else {
            BlockQueue chainQueue = blockchain.getQueue();
            BigInteger peerTotalDifficulty = new BigInteger(1, msg.getTotalDifficulty());
            BigInteger highestKnownTotalDifficulty = chainQueue.getHighestTotalDifficulty();
            if (highestKnownTotalDifficulty == null
                    || peerTotalDifficulty.compareTo(highestKnownTotalDifficulty) > 0) {
                hashRetrievalLock = this.peerId;
                chainQueue.setHighestTotalDifficulty(peerTotalDifficulty);
                chainQueue.setBestHash(msg.getBestHash());
                syncStatus = SyncSatus.HASH_RETRIEVING;
                sendGetBlockHashes();
            } else
                startGetBlockTimer();
        }
    }

    private void processBlockHashes(BlockHashesMessage blockHashesMessage) {

        Blockchain blockchain = WorldManager.getInstance().getBlockchain();
        List<byte[]> receivedHashes = blockHashesMessage.getBlockHashes();
        BlockQueue chainQueue = blockchain.getQueue();

        // result is empty, peer has no more hashes
        // or peer doesn't have the best hash anymore
        if (receivedHashes.isEmpty()
                || !this.peerId.equals(hashRetrievalLock)) {
            startGetBlockTimer(); // start getting blocks from hash queue
            return;
        }

        Iterator<byte[]> hashIterator = receivedHashes.iterator();
        byte[] foundHash, latestHash = blockchain.getLatestBlockHash();
        while (hashIterator.hasNext()) {
            foundHash = hashIterator.next();
            if (FastByteComparisons.compareTo(foundHash, 0, 32, latestHash, 0, 32) != 0){
                chainQueue.addHash(foundHash);    // store unknown hashes in queue until known hash is found
            }
            else {
                // if known hash is found, ignore the rest
                startGetBlockTimer(); // start getting blocks from hash queue
                return;
            }
        }
        // no known hash has been reached
        chainQueue.logHashQueueSize();
        sendGetBlockHashes(); // another getBlockHashes with last received hash.
    }

    private void processBlocks(BlocksMessage blocksMessage) {
        Blockchain blockchain = WorldManager.getInstance().getBlockchain();
        List<Block> blockList = blocksMessage.getBlocks();

        // If we got less blocks then we could get,
        // it the correct indication that we are in sync we
        // the chain from here there will be NEW_BLOCK only
        // message expectation
        if (blockList.size() < CONFIG.maxBlocksAsk()) {
            logger.info(" *** The chain sync process fully complete ***");
            syncStatus = SyncSatus.SYNC_DONE;
            stopGetBlocksTimer();
        }

        if (blockList.isEmpty()) return;
        blockchain.getQueue().addBlocks(blockList);
        blockchain.getQueue().logHashQueueSize();
    }


    /**
     * Processing NEW_BLOCK announce message
     * @param newBlockMessage - new block message
     */
    private void procesNewBlock(NewBlockMessage newBlockMessage){

        Blockchain blockchain = WorldManager.getInstance().getBlockchain();
        Block newBlock = newBlockMessage.getBlock();

        // If the hashes still being downloaded ignore the NEW_BLOCKs
        // that block hash will be retrieved by the others and letter the block itself
        if (syncStatus == SyncSatus.INIT || syncStatus == SyncSatus.HASH_RETRIEVING) {
            logger.debug("Sync status INIT or HASH_RETREIVING ignore new block.index: [{}]", newBlock.getNumber());
            return;
        }

        // If the GET_BLOCKs stage started add hash to the end of the hash list
        // then the block will be retrieved in it's turn;
        if (syncStatus == SyncSatus.BLOCK_RETRIEVING){
            logger.debug("Sync status BLOCK_RETREIVING add to the end of hash list: block.index: [{}]", newBlock.getNumber());
            blockchain.getQueue().addNewBlockHash(newBlockMessage.getBlock().getHash());
            return;
        }

        logger.info("New block received: block.index [{}]", newBlockMessage.getBlock().getNumber());

        blockchain.getQueue().addBlock(newBlockMessage.getBlock());
        blockchain.getQueue().logHashQueueSize();
    }

    private void sendStatus(){
        Blockchain blockChain= WorldManager.getInstance().getBlockchain();
        byte protocolVersion = EthHandler.VERSION, networkId = EthHandler.NETWORK_ID;
        BigInteger totalDifficulty = blockChain.getTotalDifficulty();
        byte[] bestHash = blockChain.getLatestBlockHash();
        StatusMessage msg = new StatusMessage(protocolVersion, networkId,
                ByteUtil.bigIntegerToBytes(totalDifficulty), bestHash, Blockchain.GENESIS_HASH);
        msgQueue.sendMessage(msg);
    }

    /*
     * The wire gets data for signed transactions and
     * sends it to the net.
     */
    public void sendTransaction(Transaction transaction) {
        Set<Transaction> txs = new HashSet<>(Arrays.asList(transaction));
        TransactionsMessage msg = new TransactionsMessage(txs);
        msgQueue.sendMessage(msg);
    }

    private void sendGetTransactions() {
        msgQueue.sendMessage(GET_TRANSACTIONS_MESSAGE);
    }

    private void sendGetBlockHashes() {
        Blockchain blockchain = WorldManager.getInstance().getBlockchain();
        byte[] bestHash = blockchain.getQueue().getBestHash();
        GetBlockHashesMessage msg = new GetBlockHashesMessage(bestHash, CONFIG.maxHashesAsk());
        msgQueue.sendMessage(msg);
    }

    // Parallel download blocks based on hashQueue
    private void sendGetBlocks() {
        Blockchain blockchain = WorldManager.getInstance().getBlockchain();
        BlockQueue queue = blockchain.getQueue();
        if (queue.size() > CONFIG.maxBlocksQueued()) return;

        // retrieve list of block hashes from queue
        List<byte[]> hashes = queue.getHashes();
        if (hashes.isEmpty()) {
            stopGetBlocksTimer();
            return;
        }

        GetBlocksMessage msg = new GetBlocksMessage(hashes);
        msgQueue.sendMessage(msg);
    }

    private void sendPendingTransactions() {
        Set<Transaction> pendingTxs =
                WorldManager.getInstance().getPendingTransactions();
        TransactionsMessage msg = new TransactionsMessage(pendingTxs);
        msgQueue.sendMessage(msg);
    }

    private void sendBlocks() {
        // TODO: Send blocks
    }

    private void sendBlockHashes() {
        // TODO: Send block hashes
    }

    private void startTxTimer() {
        getTxTimer.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                sendGetTransactions();
            }
        }, 2000, 10000);
    }

    public void startGetBlockTimer() {
        syncStatus = SyncSatus.BLOCK_RETRIEVING;
        getBlocksTimer.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                BlockQueue blockQueue = WorldManager.getInstance().getBlockchain().getQueue();
                if (blockQueue.size() > CONFIG.maxBlocksQueued()) {
                    logger.info("Blocks queue too big temporary postpone blocks request");
                    return;
                }
                sendGetBlocks();
            }
        }, 1000, 300);
    }

    private void stopGetBlocksTimer() {
        getBlocksTimer.cancel();
        getBlocksTimer.purge();
    }

    private void stopGetTxTimer() {
        getTxTimer.cancel();
        getTxTimer.purge();
    }

    public void killTimers() {
        stopGetBlocksTimer();
        stopGetTxTimer();
    }


    private enum SyncSatus{
        INIT,
        HASH_RETRIEVING,
        BLOCK_RETRIEVING,
        SYNC_DONE;
    }
}