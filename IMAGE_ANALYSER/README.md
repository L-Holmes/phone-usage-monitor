
# One-time setup
Run:
```
chmod +x setup.sh && ./setup.sh
```

# Running 
```
# default level (moderate)
cargo run --release -- --test-models                 

# strict | moderate | lenient
cargo run --release -- --test-models --level strict  

# running on a single file (default behaviour)
cargo run --release -- --file test/static/testImages/not-allowed5.jpeg
```

