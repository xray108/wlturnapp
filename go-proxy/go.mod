module wlproxy

go 1.22

require (
	github.com/pion/dtls/v2 v2.2.7
	github.com/pion/logging v0.2.2
	github.com/pion/turn/v2 v2.1.3
    github.com/pion/transport/v2 v2.2.1 // For potential packet handling
)