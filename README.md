# AOChatProxy
Anarchy Online Proxy Bot that load-balances the friendlist across multiple characters so that other programs don't have to manage that

# To build
From the AOChatProxy directory, run this command:
```
docker run -it --rm --name ao-chat-proxy-build -v ~/.m2:/root/.m2 -v "$(pwd)":/usr/src/mymaven -w /usr/src/mymaven maven:3.6.3-jdk-11-slim mvn -e package
```

If you get an error about an unresolved TyrLib dependency, you should follow the instructions on the https://github.com/Budabot/TyrLib project for installing it locally first.
