package conflux.web3j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import conflux.web3j.types.Address;
import org.web3j.crypto.CipherException;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.crypto.Wallet;
import org.web3j.crypto.WalletFile;
import org.web3j.crypto.WalletUtils;
import org.web3j.utils.Numeric;

import com.fasterxml.jackson.databind.ObjectMapper;

import conflux.web3j.types.AddressType;
import conflux.web3j.types.RawTransaction;

/**
 * AccountManager manages Conflux accounts at local file system.
 *
 */
public class AccountManager {
	
	private static final ObjectMapper objectMapper = new ObjectMapper();
	private static final String keyfilePrefix = "conflux-keyfile-";
	private static final String keyfileExt = ".json";
	
	// directory to store the key files.
	private String dir;
	private Path dirPath;
	// unlocked accounts: map<address, item>
	private ConcurrentHashMap<String, UnlockedItem> unlocked;

	private int networkId;
	
	/**
	 * Create a AccountManager instance with default directory.
	 * @param networkId networkId
	 * @throws Exception if failed to create directories.
	 */
	public AccountManager(int networkId) throws Exception {
		this(getDefaultDirectory(), networkId);
	}
	
	/**
	 * Create a AccountManager instance with specified directory.
	 * @param dir directory to store key files.
	 * @param networkId networkId
	 * @throws IOException if failed to create directories.
	 */
	public AccountManager(String dir, int networkId) throws IOException {
		Path p = Paths.get(dir).normalize();
		Files.createDirectories(p);
		this.dir = p.toString();
		this.dirPath = p;
		this.networkId = networkId;
		this.unlocked = new ConcurrentHashMap<String, UnlockedItem>();
	}
	
	/**
	 * Get the directory to store key files.
	 * @return directory path.
	 */
	public String getDirectory() {
		return this.dir;
	}
	
	/**
	 * Get the default directory to store key files.
	 * @return directory path.
	 */
	public static String getDefaultDirectory() {
		String osName = System.getProperty("os.name").toLowerCase();
		
		if (osName.startsWith("mac")) {
            return String.format("%s%sLibrary%sConflux", System.getProperty("user.home"), File.separator, File.separator);
        } else if (osName.startsWith("win")) {
            return String.format("%s%sConflux", System.getenv("APPDATA"), File.separator);
        } else {
            return String.format("%s%s.conflux", System.getProperty("user.home"), File.separator);
        }
	}
	
	/**
	 * Create a new account with specified password.
	 * @param password used to encrypt the key file.
	 * @return address of new created account.
	 * @throws Exception if failed to create file
	 */
	public Address create(String password) throws Exception {
		return this.createKeyFile(password, Keys.createEcKeyPair());
	}
	
	protected Address createKeyFile(String password, ECKeyPair ecKeyPair) throws Exception {
		WalletFile walletFile = Wallet.createStandard(password, ecKeyPair);
		walletFile.setAddress(AddressType.User.normalize(walletFile.getAddress()));

		String filename = String.format("%s%s%s", keyfilePrefix, walletFile.getAddress(), keyfileExt);
		File keyfile = new File(this.dir, filename);
		objectMapper.writeValue(keyfile, walletFile);

		return new Address(walletFile.getAddress(), this.networkId);
	}
	
	/**
	 * List all managed accounts.
	 * @return list of addresses of all managed accounts.
	 * @throws IOException if read files failed
	 */
	public List<Address> list() throws IOException {
		return Files.list(this.dirPath)
				.map(path -> this.parseAddressFromFilename(path.getFileName().toString()))
				.filter(path -> !path.isEmpty())
				.sorted()
				.map(hexAddress -> new Address(hexAddress, this.networkId))
				.collect(Collectors.toList());
	}
	
	/**
	 * Parse the address from the specified key file name.
	 * @param filename key file name.
	 * @return account address of the key file.
	 */
	protected String parseAddressFromFilename(String filename) {
		if (!filename.startsWith(keyfilePrefix) || !filename.endsWith(keyfileExt)) {
			return "";
		}
		
		String hexAddress = filename.substring(keyfilePrefix.length(), filename.length() - keyfileExt.length());
		
		try {
			AddressType.validateHexAddress(hexAddress, AddressType.User);
		} catch (Exception e) {
			return "";
		}
		
		return hexAddress;
	}
	
	private Optional<Address> imports(Credentials credentials, String password) throws Exception {
		String hexAddress = AddressType.User.normalize(credentials.getAddress());
		Address address = new Address(hexAddress, this.networkId);
		if (this.exists(address)) {
			return Optional.empty();
		}
		
		this.createKeyFile(password, credentials.getEcKeyPair());
		
		return Optional.of(address);
	}
	
	/**
	 * Import unmanaged account from external key file.
	 * @param keyFile key file path.
	 * @param password decrypt the external key file.
	 * @param newPassword encrypt the new created/managed key file.
	 * @return imported account address if not exists. Otherwise, return <code>Optional.empty()</code>.
	 * @throws Exception if load keyFile failed
	 */
	public Optional<Address> imports(String keyFile, String password, String newPassword) throws Exception {
		Credentials importedCredentials = WalletUtils.loadCredentials(password, keyFile);
		return this.imports(importedCredentials, newPassword);
	}
	
	/**
	 * Import unmanaged account from a private key.
	 * @param privateKey private key to import.
	 * @param password encrypt the new created/managed key file.
	 * @return imported account address if not exists. Otherwise, return <code>Optional.empty()</code>.
	 * @throws Exception if create file failed
	 */
	public Optional<Address> imports(String privateKey, String password) throws Exception {
		return this.imports(Credentials.create(privateKey), password);
	}
	
	/**
	 * Check whether the specified account address is managed.
	 * @param address account address.
	 * @return <code>true</code> if the specified account address is managed. Otherwise, <code>false</code>.
	 * @throws Exception if read file failed
	 */
	public boolean exists(Address address) throws Exception {
		return Files.list(this.dirPath)
				.map(path -> this.parseAddressFromFilename(path.getFileName().toString()))
				.anyMatch(path -> path.equalsIgnoreCase(address.getHexAddress()));
	}
	
	/**
	 * Delete the key file of specified account from key store.
	 * It will also clear the record if the specified account is unlocked.
	 * @param address account address to delete.
	 * @return <code>false</code> if the specified account not found. Otherwise, <code>true</code>.
	 * @throws Exception  if file delete failed
	 */
	public boolean delete(Address address) throws Exception {
		String hexAddress = address.getHexAddress();
		List<Path> files = Files.list(this.dirPath)
				.filter(path -> this.parseAddressFromFilename(path.getFileName().toString()).equalsIgnoreCase(hexAddress))
				.collect(Collectors.toList());
		
		if (files.isEmpty()) {
			return false;
		}
		
		for (Path file : files) {
			Files.delete(file.normalize());
		}
		
		this.unlocked.remove(hexAddress);
		
		return true;
	}
	
	/**
	 * Update the password of key file for the specified account address.
	 * @param address account address to update key file.
	 * @param password password to decrypt the original key file.
	 * @param newPassword password to encrypt the new key file.
	 * @return <code>false</code> if the specified account not found. Otherwise, <code>true</code>.
	 * @throws Exception if file read failed
	 */
	public boolean update(Address address, String password, String newPassword) throws Exception {
		List<Path> files = Files.list(this.dirPath)
				.filter(path -> this.parseAddressFromFilename(path.getFileName().toString()).equalsIgnoreCase(address.getHexAddress()))
				.collect(Collectors.toList());
		
		if (files.isEmpty()) {
			return false;
		}
		
		ECKeyPair ecKeyPair = WalletUtils.loadCredentials(password, files.get(0).toString()).getEcKeyPair();
		Files.delete(files.get(0).normalize());
		this.createKeyFile(newPassword, ecKeyPair);
		
		return true;
	}
	
	/**
	 * Export private key for the specified account address.
	 * @param address account address to export private key.
	 * @param password to decrypt the original key file.
	 * @return private key if account exists. Otherwise, <code>null</code>.
	 * @throws Exception if file read failed
	 */
	public String exportPrivateKey(Address address, String password) throws Exception {
		List<Path> files = Files.list(this.dirPath)
				.filter(path -> this.parseAddressFromFilename(path.getFileName().toString()).equalsIgnoreCase(address.getHexAddress()))
				.collect(Collectors.toList());
		
		if (files.isEmpty()) {
			return null;
		}
		
		ECKeyPair ecKeyPair = WalletUtils.loadCredentials(password, files.get(0).toString()).getEcKeyPair();
		return "0x" + ecKeyPair.getPrivateKey().toString(16);
	}
	
	/**
	 * Unlock the specified account for a period to allow signing multiple transactions at a time.
	 * @param address account address to unlock.
	 * @param password decrypt the key file.
	 * @param timeout a period of time to unlock the account. Empty timeout indicates unlock the account indefinitely.
	 * @return <code>false</code> if the specified account not found. Otherwise, <code>true</code>.
	 * @throws Exception if file read failed
	 */
	public boolean unlock(Address address, String password, Duration... timeout) throws Exception {
		String hexAddress = address.getHexAddress();
		List<Path> files = Files.list(this.dirPath)
				.filter(path -> this.parseAddressFromFilename(path.getFileName().toString()).equalsIgnoreCase(hexAddress))
				.collect(Collectors.toList());
		
		if (files.isEmpty()) {
			return false;
		}
		
		Credentials credentials = WalletUtils.loadCredentials(password, files.get(0).toString());
		
		UnlockedItem item;
		
		if (timeout != null && timeout.length > 0 && timeout[0] != null && timeout[0].compareTo(Duration.ZERO) > 0) {
			item = new UnlockedItem(credentials.getEcKeyPair(), Optional.of(timeout[0]));
		} else {
			item = new UnlockedItem(credentials.getEcKeyPair(), Optional.empty());
		}
		
		this.unlocked.put(hexAddress, item);
		
		return true;
	}
	
	/**
	 * Lock the specified account.
	 * @param address account address to lock.
	 * @return <code>true</code> if the specified account has already been unlocked and not expired. Otherwise, <code>false</code>.
	 */
	public boolean lock(Address address) {
		UnlockedItem item = this.unlocked.remove(address.getHexAddress());
		return item != null && !item.expired();
	}
	
	/**
	 * Sign a raw transaction with specified account.
	 * @param tx transaction to sign with.
	 * @param address account address to sign the transaction.
	 * @param password decrypt the key file. If empty, the account should be unlocked already.
	 * @return signed and RLP encoded transaction.
	 * @exception IllegalArgumentException if account not found, or password not specified for locked account, or password expired for unlocked account.
	 * @throws Exception if get keypair failed
	 */
	public String signTransaction(RawTransaction tx, Address address, String... password) throws Exception {
		ECKeyPair ecKeyPair = this.getEcKeyPair(address, password);
		return tx.sign(ecKeyPair);
	}
	
	private ECKeyPair getEcKeyPair(Address cfxAddress, String... password) throws IOException, CipherException {
		String address = cfxAddress.getHexAddress();
		UnlockedItem item = this.unlocked.get(address);
		
		if (password == null || password.length == 0 || password[0] == null || password[0].isEmpty()) {	
			if (item == null) {
				throw new IllegalArgumentException("password not specified for locked account");
			}
			
			if (item.expired()) {
				this.unlocked.remove(address);
				throw new IllegalArgumentException("password expired for unlocked account");
			}
			
			return item.getEcKeyPair();
		} else {
			if (item != null) {
				if (!item.expired()) {
					return item.getEcKeyPair();
				}
				
				this.unlocked.remove(address);
			}
			
			List<Path> files = Files.list(this.dirPath)
					.filter(path -> this.parseAddressFromFilename(path.getFileName().toString()).equalsIgnoreCase(address))
					.collect(Collectors.toList());
			
			if (files.isEmpty()) {
				throw new IllegalArgumentException("account not found");
			}
			
			return WalletUtils.loadCredentials(password[0], files.get(0).toString()).getEcKeyPair();
		}
	}
	
	public String signMessage(byte[] message, boolean needToHash, Address address, String... password) throws Exception {
		ECKeyPair ecKeyPair = this.getEcKeyPair(address, password);
		return signMessage(message, needToHash, ecKeyPair);
	}
	
	public static String signMessage(byte[] message, boolean needToHash, ECKeyPair ecKeyPair) {
		Sign.SignatureData data = Sign.signMessage(message, ecKeyPair, needToHash);
		
		byte[] rsv = new byte[data.getR().length + data.getS().length + data.getV().length];
		System.arraycopy(data.getR(), 0, rsv, 0, data.getR().length);
		System.arraycopy(data.getS(), 0, rsv, data.getR().length, data.getS().length);
		System.arraycopy(data.getV(), 0, rsv, data.getR().length + data.getS().length, data.getV().length);
		
		return Numeric.toHexString(rsv);
	}
}

class UnlockedItem {
	private ECKeyPair ecKeyPair;
	private Optional<Instant> until;
	
	public UnlockedItem(ECKeyPair ecKeyPair, Optional<Duration> timeout) {
		this.ecKeyPair = ecKeyPair;
		
		if (!timeout.isPresent()) {
			this.until = Optional.empty();
		} else {
			this.until = Optional.of(Instant.now().plusNanos(timeout.get().toNanos()));
		}
	}
	
	public ECKeyPair getEcKeyPair() {
		return ecKeyPair;
	}
	
	public boolean expired() {
		return this.until.isPresent() && this.until.get().isBefore(Instant.now());
	}
}
