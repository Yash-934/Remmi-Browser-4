use adblock::request::Request;

fn main() {
    let req1 = Request::new("http://a.example.com/ad.js", "http://b.example.com", "script").unwrap();
    println!("a.example.com -> b.example.com: third_party={}", req1.is_third_party());
    
    let req2 = Request::new("http://external.example.net/ad.js", "http://b.example.com", "script").unwrap();
    println!("b.example.com -> external.example.net: third_party={}", req2.is_third_party());
}
