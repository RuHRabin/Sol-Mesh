# Oracle Cloud-এ সার্ভার বসানো (ধাপে ধাপে)

এই গাইডে Oracle Cloud-এর বিনামূল্যের ("Always Free") একটি ছোট মেশিনে sol-mesh সার্ভার চালু করা হবে।
সার্ভারটি relay (লাইভ সংযোগ ও ফাইল) এবং মেইলবক্স (অফলাইন টেক্সট মেসেজ) দুটোই করে।

> **সৎ সতর্কতা:** Oracle-এর ওয়েবসাইটের মেনুর নাম মাঝে মাঝে বদলায়। নিচের নামগুলো কাছাকাছি, হুবহু না-ও মিলতে পারে।
> কোনো ধাপে আটকে গেলে স্ক্রিনশট বা error লেখা পাঠান। Always Free-এর সীমা বদলাতে পারে, তাই সাইন-আপের সময়
> তাদের পাতায় নিজে একবার দেখে নিন।

## ধাপ ১: অ্যাকাউন্ট খোলা

1. `oracle.com/cloud/free` খুলে **Start for free** চাপুন।
2. নাম, ইমেইল ও দেশ দিন। **Home Region** বেছে নিন (বাংলাদেশ থেকে সিঙ্গাপুর, মুম্বাই বা হায়দ্রাবাদের মতো কাছের
   অঞ্চল ভালো)। **এটা পরে বদলানো যায় না।**
3. কার্ড যাচাই হবে। এটা পরিচয় যাচাইয়ের জন্য, সাধারণত অল্প টাকা কেটে ফেরত দেয়। মূল্যবান কিছু তৈরি না করলে
   (নিচের ধাপে শুধু "Always Free" লেখা জিনিস নেবেন) বিল আসার কথা নয়।

## ধাপ ২: ভার্চুয়াল মেশিন তৈরি

1. মেনু থেকে **Compute -> Instances -> Create instance**।
2. নাম দিন, যেমন `sol-mesh`।
3. **Image:** *Canonical Ubuntu 22.04* (Change image থেকে বেছে নিন)।
4. **Shape:** *Change shape* চেপে **"Always Free-eligible"** লেখা একটি বেছে নিন:
   - `VM.Standard.E2.1.Micro` (AMD, ছোট, প্রায় সবসময় পাওয়া যায়), অথবা
   - `VM.Standard.A1.Flex` (Ampere ARM, ১ OCPU ও ৬ GB যথেষ্ট)।

   আমাদের সার্ভার খুবই হালকা, ছোটটাই চলবে।
5. **Networking:** ডিফল্ট রাখুন, আর নিশ্চিত করুন **Public IPv4 address** চালু আছে।
6. **SSH keys:** *Generate a key pair for me* চেপে **private key ফাইলটি ডাউনলোড করে সযত্নে রাখুন।** এটা হারালে মেশিনে
   ঢুকতে পারবেন না।
7. **Create** চাপুন। যদি **"Out of capacity"** লেখা আসে, তার মানে ওই অঞ্চলে এখন খালি মেশিন নেই। অন্য Shape
   (E2.1.Micro বা A1.Flex) চেষ্টা করুন, কিছুক্ষণ পরে আবার চেষ্টা করুন।
8. মেশিন চালু হলে পাতায় **Public IP address** (যেমন `129.x.x.x`) দেখা যাবে। এটা টুকে রাখুন।

## ধাপ ৩: ৯০০০ নম্বর পোর্ট খোলা (দুই জায়গায়)

**(ক) Oracle-এর নেটওয়ার্কে:**
1. মেশিনের পাতায় **Subnet** লিংকে চাপুন, তারপর **Default Security List**।
2. **Add Ingress Rules:** Source CIDR `0.0.0.0/0`, IP Protocol `TCP`, Destination Port Range `9000`। সংরক্ষণ করুন।

**(খ) মেশিনের ভেতরের firewall-এ** (ধাপ ৪-এ ঢুকে চালাবেন):
```
sudo iptables -I INPUT 1 -p tcp --dport 9000 -j ACCEPT
sudo apt install -y iptables-persistent
sudo netfilter-persistent save
```
(`iptables-persistent` ইনস্টলের সময় প্রশ্ন এলে Enter চাপুন।)

## ধাপ ৪: মেশিনে ঢোকা ও সার্ভার চালানো

আপনার কম্পিউটারে (Windows হলে PowerShell, Mac/Linux হলে Terminal):

```
ssh -i path/to/ডাউনলোড-করা-private-key ubuntu@আপনার-PUBLIC-IP
```

ঢুকে চালান:

```
sudo apt update
sudo apt install -y python3
sudo useradd --system --home /opt/sol-mesh --shell /usr/sbin/nologin solmesh
sudo mkdir -p /opt/sol-mesh
sudo chown solmesh /opt/sol-mesh
```

এবার আপনার কম্পিউটার থেকে `relay` ফোল্ডারের দুটি ফাইল মেশিনে পাঠান (নতুন একটি টার্মিনাল খুলে):

```
scp -i path/to/private-key relay_server.py sol-mesh.service ubuntu@আপনার-PUBLIC-IP:/tmp/
```

আবার মেশিনের ভেতরে:

```
sudo mv /tmp/relay_server.py /opt/sol-mesh/
sudo mv /tmp/sol-mesh.service /etc/systemd/system/
sudo chown solmesh /opt/sol-mesh/relay_server.py
sudo systemctl daemon-reload
sudo systemctl enable --now sol-mesh
sudo systemctl status sol-mesh
```

`active (running)` দেখালে সার্ভার চালু। পরে লগ দেখতে: `sudo journalctl -u sol-mesh -f`

## ধাপ ৫: অ্যাপে ঠিকানা দেওয়া

1. দুই ফোনেই অ্যাপ খুলুন, **Home -> Relay** ট্যাব।
2. ঠিকানার ঘরে লিখুন `আপনার-PUBLIC-IP:9000`।
3. কিছুক্ষণ পরে **"Offline messages: Connected"** দেখাবে। না দেখালে নিচের "সমস্যা" অংশ দেখুন।
4. দুই বন্ধু আগে অন্তত একবার সরাসরি connect হয়ে নিন (একই Wi-Fi-তে বা কোড দিয়ে), তাহলে তারা একে অপরের চাবি জানবে।
5. এবার একজন অ্যাপ বন্ধ বা অফলাইন করলে অন্যজনের পাঠানো টেক্সট সার্ভারে জমা থাকবে, আর ফিরলেই সাথে সাথে পৌঁছাবে।

## সমস্যা হলে

| লক্ষণ | সম্ভাব্য কারণ |
|---|---|
| "Server unreachable" | পোর্ট ৯০০০ দুই জায়গার (ক ও খ) একটিতে খোলা হয়নি, অথবা IP ভুল |
| "Your phone's clock is wrong" | ফোনের সময় সার্ভারের সময় থেকে ৫ মিনিটের বেশি আলাদা। ফোনের সময় "স্বয়ংক্রিয়" করুন |
| "does not support offline messages" | সার্ভারে পুরনো `relay_server.py` আছে। নতুনটা দিয়ে বদলে `sudo systemctl restart sol-mesh` চালান |
| ঠিকানা বদলে গেছে | মেশিন নতুন করে বানালে IP বদলায়। Oracle-এ "Reserved public IP" নেওয়া যায় (সীমা তাদের পাতায় দেখুন) |

## মনে রাখার বিষয়

- **Oracle কম ব্যবহৃত বিনামূল্যের মেশিন কেটে দিতে পারে**, আর অ্যাকাউন্টও কখনো বন্ধ হয়েছে বলে শোনা যায়। এই সার্ভারে
  মেসেজ সাময়িক জমা থাকে, তাই মেশিন হারালে শুধু না-পৌঁছানো মেসেজ যাবে। নতুন মেশিনে এই গাইড আবার অনুসরণ করুন,
  আর দুই ফোনে নতুন IP বসান।
- সার্ভার আপনার, তাই কে কার সাথে কখন কথা বলে সেই তথ্য শুধু আপনার কাছে থাকে। মেসেজের ভেতরের লেখা সার্ভার পড়তে
  পারে না।
- সার্ভার আপডেট করতে: নতুন `relay_server.py` ধাপ ৪-এর মতো পাঠিয়ে `sudo systemctl restart sol-mesh` চালান।
  জমা থাকা মেসেজ `mailbox.db`-তে থাকে, তাই আপডেটে হারায় না।
