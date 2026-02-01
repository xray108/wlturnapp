package wlproxy

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/dtls/v2"
	"github.com/pion/dtls/v2/pkg/crypto/selfsign"
	"github.com/pion/logging"
	"github.com/pion/turn/v2"
)

// TurnProxy manages the TURN/DTLS proxy
type TurnProxy struct {
	Peer   string
	Link   string
	ListenAddr string
	N      int
	Udp    bool
	Realm  string

	wg         sync.WaitGroup
	listenConn net.PacketConn
	cancel     context.CancelFunc
}

// NewTurnProxy creates a new TurnProxy instance. 
func NewTurnProxy(peer, link, listen string) *TurnProxy {
	return &TurnProxy{
		Peer:       peer,
		Link:       link,
		ListenAddr: listen,
		N:          1,
		Udp:        true,
		Realm:      "my-realm",
	}
}

// Logger interface for Android callbacks
type Logger interface {
	Log(string)
}

var logger Logger

// SetLogger sets the logger for Android
func SetLogger(l Logger) {
	logger = l
	log.SetOutput(&logWriter{})
}

type logWriter struct{}

func (w *logWriter) Write(p []byte) (n int, err error) {
	if logger != nil {
		logger.Log(string(p))
	}
	return len(p), nil
}

func (p *TurnProxy) Start() error {
	ctx, cancel := context.WithCancel(context.Background())
	p.cancel = cancel

	var err error
	peerAddr, err := net.ResolveUDPAddr("udp", p.Peer)
	if err != nil {
		return fmt.Errorf("resolve peer: %w", err)
	}

	p.listenConn, err = net.ListenPacket("udp", p.ListenAddr)
	if err != nil {
		return fmt.Errorf("listen: %w", err)
	}

	okchan := make(chan struct{}, p.N*2)
	connchan := make(chan net.PacketConn, p.N*2)
	errchan := make(chan error, p.N*2)

	p.wg.Add(p.N + 1) // DTLS + manager

	for i := 0; i < p.N; i++ {
		go func() {
			defer p.wg.Done()
			oneDtlsConnection(ctx, peerAddr, p.listenConn, connchan, okchan, errchan)
		}()
	}

	go func() {
		defer p.wg.Done()
		for {
			select {
			case conn := <-connchan:
				p.wg.Add(1)
				go func(c net.PacketConn) {
					defer p.wg.Done()
					oneTurnConnection(ctx, "", "", p.Link, p.Udp, p.Realm, peerAddr, c, errchan)
				}(conn)
			case <-okchan:
				log.Printf("DTLS connection established")
			case err := <-errchan:
				log.Printf("Connection error: %v", err)
				cancel()
				return
			case <-ctx.Done():
				return
			}
		}
	}()

	p.wg.Wait()
	return nil
}

func (p *TurnProxy) Stop() {
	if p.cancel != nil {
		p.cancel()
	}
	if p.listenConn != nil {
		p.listenConn.Close()
	}
	p.wg.Wait()
}

func doRequest(data string, url string) (resp map[string]interface{}, err error) {
	client := &http.Client{
		Timeout: 20 * time.Second,
		Transport: &http.Transport{
			MaxIdleConns:        100,
			MaxIdleConnsPerHost: 100,
			IdleConnTimeout:     90 * time.Second,
		},
	}
	req, err := http.NewRequest("POST", url, bytes.NewBuffer([]byte(data)))
	if err != nil {
		return nil, err
	}
	req.Header.Add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0")
	req.Header.Add("Content-Type", "application/x-www-form-urlencoded")

	httpResp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer httpResp.Body.Close()

	body, err := io.ReadAll(httpResp.Body)
	if err != nil {
		return nil, err
	}

	err = json.Unmarshal(body, &resp)
	if err != nil {
		return nil, err
	}
	return resp, nil
}

func getCreds(link string) (string, string, string, error) {
	// Mock implementation for getCreds as requested.
	// In a real scenario, this would parse the link and fetch tokens.
	return "user", "pass", "turn:127.0.0.1:3478", nil 
}

func dtlsFunc(ctx context.Context, conn net.PacketConn, _ *net.UDPAddr) (net.Conn, error) {
	certificate, err := selfsign.GenerateSelfSigned()
	if err != nil {
		return nil, err
	}
	config := &dtls.Config{
		Certificates:          []tls.Certificate{certificate},
		InsecureSkipVerify:    true,
		ExtendedMasterSecret:  dtls.RequireExtendedMasterSecret,
		CipherSuites:          []dtls.CipherSuiteID{dtls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256},
	}
	
	// conn is *packetConnWrap which implements net.Conn
	netConn, ok := conn.(net.Conn)
	if !ok {
		return nil, fmt.Errorf("conn does not implement net.Conn")
	}

	dtlsConn, err := dtls.Client(netConn, config)
	if err != nil {
		return nil, err
	}

	return dtlsConn, nil
}

func oneDtlsConnection(ctx context.Context, peer *net.UDPAddr, listenConn net.PacketConn, connchan chan<- net.PacketConn, okchan chan<- struct{}, c1 chan<- error) {
	var err error
	defer func() { c1 <- err }()
	dtlsctx, dtlscancel := context.WithCancel(ctx)
	defer dtlscancel()
	
	// Local implementation of AsyncPacketPipe
	conn1, conn2 := newAsyncPacketPipe()
	
	go func() {
		for {
			select {
			case <-dtlsctx.Done():
				return
			case connchan <- conn2:
			}
		}
	}()
	dtlsConn, err1 := dtlsFunc(dtlsctx, conn1, peer)
	if err1 != nil {
		err = fmt.Errorf("failed to connect DTLS: %w", err1)
		return
	}
	defer dtlsConn.Close()
	log.Printf("Established DTLS connection!")
	go func() {
		for {
			select {
			case <-dtlsctx.Done():
				return
			case okchan <- struct{}{}:
			}
		}
	}()

	wg := sync.WaitGroup{}
	wg.Add(2)
	context.AfterFunc(dtlsctx, func() {
		listenConn.SetDeadline(time.Now())
		dtlsConn.SetDeadline(time.Now())
	})
	var addr atomic.Value
	go func() {
		defer wg.Done()
		defer dtlscancel()
		buf := make([]byte, 1600)
		for {
			select {
			case <-dtlsctx.Done():
				return
			default:
			}
			n, addr1, err1 := listenConn.ReadFrom(buf)
			if err1 != nil {
				return
			}
			addr.Store(addr1)
			_, err1 = dtlsConn.Write(buf[:n])
			if err1 != nil {
				return
			}
		}
	}()
	go func() {
		defer wg.Done()
		defer dtlscancel()
		buf := make([]byte, 1600)
		for {
			select {
			case <-dtlsctx.Done():
				return
			default:
			}
			n, err1 := dtlsConn.Read(buf)
			if err1 != nil {
				return
			}
			addr1, ok := addr.Load().(net.Addr)
			if !ok {
				return
			}
			_, err1 = listenConn.WriteTo(buf[:n], addr1)
			if err1 != nil {
				return
			}
		}
	}()
	wg.Wait()
	listenConn.SetDeadline(time.Time{})
	dtlsConn.SetDeadline(time.Time{})
}

func oneTurnConnection(ctx context.Context, host, port, link string, udp bool, realm string, peer net.Addr, conn2 net.PacketConn, c chan<- error) {
	var err error
	defer func() { c <- err }()
	user, pass, url, err1 := getCreds(link)
	if err1 != nil {
		err = err1
		return
	}
	turnServerAddr := url
	if len(url) > 5 && url[:5] == "turn:" {
		turnServerAddr = url[5:]
	}
	
	if host != "" && port != "" {
		turnServerAddr = net.JoinHostPort(host, port)
	}
	turnServerUdpAddr, err := net.ResolveUDPAddr("udp", turnServerAddr)
	if err != nil {
		return
	}
	turnServerAddr = turnServerUdpAddr.String()

	var turnConn net.PacketConn
	if udp {
		turnConn, err = net.ListenPacket("udp", "")
		if err != nil {
			return
		}
	} else {
		// Not implementing TCP for now as it's not in the snippet and adds complexity
		// But snippet had it:
		d := net.Dialer{}
		conn, err2 := d.DialContext(ctx, "tcp", turnServerAddr)
		if err2 != nil {
			err = err2
			return
		}
		turnConn = turn.NewSTUNConn(conn)
	}
	defer turnConn.Close()

	cfg := &turn.ClientConfig{
		STUNServerAddr: turnServerAddr,
		TURNServerAddr: turnServerAddr,
		Conn:           turnConn,
		Username:       user,
		Password:       pass,
		Realm:          realm,
		LoggerFactory:  logging.NewDefaultLoggerFactory(),
	}

	client, err := turn.NewClient(cfg)
	if err != nil {
		return
	}
	defer client.Close()

	err = client.Listen()
	if err != nil {
		return
	}

	relayConn, err := client.Allocate()
	if err != nil {
		return
	}
	defer relayConn.Close()

	log.Printf("relayed-address=%s", relayConn.LocalAddr().String())

	wg := sync.WaitGroup{}
	wg.Add(2)

	go func() {
		defer wg.Done()
		buf := make([]byte, 1600)
		for {
			select {
			case <-ctx.Done():
				return
			default:
			}
			n, _, err := conn2.ReadFrom(buf)
			if err != nil {
				return
			}
			_, err = relayConn.WriteTo(buf[:n], peer)
			if err != nil {
				return
			}
		}
	}()

	go func() {
		defer wg.Done()
		buf := make([]byte, 1600)
		for {
			select {
			case <-ctx.Done():
				return
			default:
			}
			n, _, err := relayConn.ReadFrom(buf)
			if err != nil {
				return
			}
			_, err = conn2.WriteTo(buf[:n], nil) // conn2 is a pipe, WriteTo might ignore addr or we use Write
			if err != nil {
				return
			}
		}
	}()

	wg.Wait()
}

// Simple AsyncPacketPipe implementation
func newAsyncPacketPipe() (net.PacketConn, net.PacketConn) {
	c1, c2 := net.Pipe()
	return &packetConnWrap{c1}, &packetConnWrap{c2}
}

type packetConnWrap struct {
	net.Conn
}

func (p *packetConnWrap) ReadFrom(b []byte) (n int, addr net.Addr, err error) {
	n, err = p.Conn.Read(b)
	return n, p.Conn.RemoteAddr(), err
}

func (p *packetConnWrap) WriteTo(b []byte, addr net.Addr) (n int, err error) {
	return p.Conn.Write(b)
}

func (p *packetConnWrap) Close() error {
	return p.Conn.Close()
}
